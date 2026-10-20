import { useCallback, useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { M3Button } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { Timestamp } from "../../shell/Timestamp";
import {
  getSystemLogSettings,
  saveSystemLogSettings,
  type ApiError,
  type SystemLogSettingKey,
  type SystemLogSettingsPatch,
  type SystemLogSettingsView,
} from "../../auth/adminApi";

const MIB = 1024 * 1024;
/** Device jobs run only in these windows (PO 2026-10-08), so a switch takes effect at the next one. */
const WINDOWS = "00:00, 06:00, 12:00 and 18:00 Europe/Istanbul";

type Kind = "days" | "mib" | "switch";

interface Row {
  readonly key: SystemLogSettingKey;
  readonly label: string;
  readonly kind: Kind;
  /** The plain explanation under the control: what the value governs and what happens at its edge. */
  readonly explanation: string;
  /** For a switch: the confirm step shown before it is turned on, saying what starts happening. */
  readonly confirm?: { readonly title: string; readonly starts: readonly string[] };
}

/** The documented settings (contract sections 5.3, 2.3 and 8.3), in the order the row stores them. */
export const SYSTEM_LOG_SETTING_ROWS: readonly Row[] = [
  {
    key: "raw_retention_days", label: "Raw log retention", kind: "days",
    explanation: "How long the collected log chunks (Check Point messages and the other incremental sources, Palo Alto system and config logs) and the daily sar files are kept. Older chunks are pruned by the retention job and every pruning is on the ledger.",
  },
  {
    key: "device_cap_bytes", label: "Per-device storage cap", kind: "mib",
    explanation: "Stored, compressed bytes one device may hold across its chunks. At the cap the oldest chunks are pruned before their retention ends (RETENTION_SHORTENED_BY_CAP on the ledger); new data is never refused for the cap.",
  },
  {
    key: "hcp_retention_days", label: "HCP report retention", kind: "days",
    explanation: "How long the Health Check Point archives fetched in the 06:00 window are kept: at most 7 archives per gateway.",
  },
  {
    key: "spike_data_retention_days", label: "Spike Detective data retention", kind: "days",
    explanation: "How long a fetched Spike Detective data directory (one encrypted archive per directory) is kept.",
  },
  {
    key: "spike_data_cap_bytes", label: "Spike Detective storage cap", kind: "mib",
    explanation: "Stored bytes of Spike Detective archives one gateway may hold.",
  },
  {
    key: "ledger_retention_days", label: "Ledger retention", kind: "days",
    explanation: "How long gap, run, crash-entry and crash-alert rows are kept. These rows hold counts and outcomes, never log text.",
  },
  {
    key: "include_management_servers", label: "Collect from management servers", kind: "switch",
    explanation: "Off: a collection request for a management server (MDS) is refused with SYSTEM_LOG_DISABLED_ON_MANAGEMENT, manual or scheduled. The contract (section 2.3) asks for an MDS measurement and MDS gate rows before this is switched on.",
    confirm: {
      title: "Include management servers in system-log collection?",
      starts: [
        `From the next job window (${WINDOWS}) every scheduled and manual collection run includes the enrolled management servers.`,
        "Each run opens one SSH session per management server and reads only through approved gate rows; where a management server has no gate row of its own, its run ends UNKNOWN (GATE_NOT_KNOWN) and stores nothing.",
        "What is read is stored encrypted under the retention and the per-device cap above, and counts against the artefact volume.",
        "The change is written to the audit log under your session.",
      ],
    },
  },
  {
    key: "spike_data_enabled", label: "Fetch Spike Detective data", kind: "switch",
    explanation: "Off: a gateway run only lists the Spike Detective directory and records the directories present as a baseline. On: new, settled data directories are listed and their perf_*.log and profiling_summary.elg files are fetched (contract section 8.3).",
    confirm: {
      title: "Fetch Spike Detective data from the gateways?",
      starts: [
        `From the next job window (${WINDOWS}) every Check Point gateway run, after its log reads, lists the Spike Detective directory (command C4, once per run). A gateway's first listing is a baseline: those directories are recorded and never fetched.`,
        "For each new data directory that is at least 10 minutes old the run lists it (C5) and fetches its perf_*.log and profiling_summary.elg files (C8): at most 16 directories per run and 16 MiB per directory; the rest waits as BACKLOG.",
        "Each directory is stored as one encrypted archive, kept for the Spike Detective retention within the Spike Detective cap per gateway above. Nothing on the gateway is changed.",
        "The change is written to the audit log under your session.",
      ],
    },
  },
];

function problemsOf(err: unknown): { readonly message: string; readonly code: string | null } {
  const e = err as ApiError | undefined;
  const body = (e?.body ?? {}) as { problems?: string[]; message?: string; code?: string; error?: string };
  if (body.problems?.length) return { message: body.problems.join("; "), code: body.code ?? null };
  if (typeof body.message === "string") return { message: body.message, code: body.code ?? null };
  return { message: `request failed${e?.status ? ` (status ${e.status})` : ""}`, code: body.code ?? body.error ?? null };
}

function mib(bytes: number): number {
  return Math.round(bytes / MIB);
}

function describeBytes(bytes: number): string {
  const m = mib(bytes);
  return m >= 1024 && m % 1024 === 0 ? `${m} MiB (${m / 1024} GiB)` : `${m} MiB`;
}

/**
 * Administration › Platform › System logs: the one settings row of the system-log programme (contract section 14,
 * `GET/PUT /api/v2/config/system-logs`; CLI `syslog-settings` / `syslog-settings-set`). Every setting shows its plain
 * explanation, the stored value and the last change. Edit controls appear only when the server says this session may
 * write (`can_write`); turning on either switch goes through a confirm step that says what starts happening.
 */
export function SystemLogSettingsPanel() {
  const [view, setView] = useState<SystemLogSettingsView | null>(null);
  const [draft, setDraft] = useState<SystemLogSettingsPatch>({});
  const [restricted, setRestricted] = useState(false);
  const [loadError, setLoadError] = useState<{ message: string; code: string | null } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<{ message: string; code: string | null } | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<readonly Row[] | null>(null);

  const load = useCallback(() => {
    setLoadError(null);
    getSystemLogSettings().then((s) => { setView(s); setDraft({}); }).catch((e) => {
      if (isRestricted(e)) { setRestricted(true); return; }
      setLoadError(problemsOf(e));
    });
  }, []);
  useEffect(() => { load(); }, [load]);

  if (restricted) {
    return <RestrictedPanel area="System logs" role="Security Admin"
      nextStep="A Security Admin changes these settings here or with the CLI (syslog-settings-set)." />;
  }
  if (loadError) {
    return <StatePanel variant="error" title="System-log settings could not be read" body={loadError.message} code={loadError.code}
      action={<M3Button emphasis="outlined" onClick={load}>Retry</M3Button>} />;
  }
  if (!view) return <Typography sx={{ p: 3 }} role="status">Loading system-log settings…</Typography>;

  const current = <K extends SystemLogSettingKey>(key: K): SystemLogSettingsView[K] =>
    (draft[key] ?? view[key]) as SystemLogSettingsView[K];
  const change = (key: SystemLogSettingKey, value: number | boolean) => {
    setNotice(null);
    setSaveError(null);
    setDraft((d) => {
      const next = { ...d, [key]: value } as SystemLogSettingsPatch;
      if (view[key] === value) delete next[key];
      return next;
    });
  };
  const patch: SystemLogSettingsPatch = Object.fromEntries(
    SYSTEM_LOG_SETTING_ROWS.map((row) => row.key).filter((key) => key in draft && draft[key] !== view[key])
      .map((key) => [key, draft[key]]),
  );
  const changed = Object.keys(patch).length > 0;

  const persist = async () => {
    setConfirm(null);
    setBusy(true);
    setNotice(null);
    setSaveError(null);
    try {
      const next = await saveSystemLogSettings(patch);
      setView(next);
      setDraft({});
      const switchedOn = SYSTEM_LOG_SETTING_ROWS.filter((row) => row.kind === "switch" && patch[row.key] === true).map((row) => row.label);
      setNotice(switchedOn.length
        ? `Settings saved. ${switchedOn.join(" and ")} starts at the next job window (${WINDOWS}).`
        : "Settings saved. Retention and caps apply from the next retention run.");
    } catch (e) {
      setSaveError(problemsOf(e));
    } finally {
      setBusy(false);
    }
  };
  const save = () => {
    if (!changed) { setNotice("Nothing changed."); return; }
    // Turning a switch on starts device commands and storage: say so first. Turning one off, or a number, saves directly.
    const starting = SYSTEM_LOG_SETTING_ROWS.filter((row) => row.confirm && patch[row.key] === true && view[row.key] === false);
    if (starting.length) setConfirm(starting);
    else void persist();
  };

  const canWrite = view.can_write;
  const bound = (key: SystemLogSettingKey, kind: Kind) => {
    const b = view.bounds?.[key];
    const scale = kind === "mib" ? MIB : 1;
    return { min: b?.min === undefined ? undefined : Math.ceil(b.min / scale), max: b?.max === undefined ? undefined : Math.floor(b.max / scale) };
  };

  return (
    <Stack spacing={2} sx={{ maxWidth: 900 }}>
      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Box>
          <Typography variant="h6" component="h3">System logs</Typography>
          <Typography variant="body2" color="text.secondary">
            What the system-log collector keeps, for how long, and the two switches that stay off until a Security Admin turns them on.
            The same row is read with the CLI command syslog-settings and changed with syslog-settings-set.
          </Typography>
        </Box>
        <Typography variant="caption" color="text.secondary" data-testid="syslog-settings-last-changed">
          Last changed <Timestamp at={view.updated_at} /> by {view.updated_by ?? "UNKNOWN"}
        </Typography>
        {!canWrite && (
          <Typography role="status" variant="body2">
            Read-only: changing these settings needs the Security Admin role. This account can read the values but not change them.
          </Typography>
        )}
        {SYSTEM_LOG_SETTING_ROWS.map((row) => {
          const { min, max } = bound(row.key, row.kind);
          const value = current(row.key);
          return (
            <Stack key={row.key} data-testid={`syslog-setting-${row.key}`} spacing={0.75} sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }}>
              {row.kind === "switch" ? (
                <FormControlLabel
                  control={<Switch checked={value as boolean} disabled={!canWrite} onChange={(e) => change(row.key, e.target.checked)} />}
                  label={row.label} />
              ) : (
                <Stack direction={{ xs: "column", sm: "row" }} spacing={2} alignItems={{ sm: "center" }}>
                  <Typography variant="body1" sx={{ minWidth: 240 }}>{row.label}</Typography>
                  {canWrite ? (
                    <TextField label={row.kind === "days" ? `${row.label} (days)` : `${row.label} (MiB)`} type="number" size="small"
                      value={row.kind === "mib" ? mib(value as number) : (value as number)}
                      onChange={(e) => {
                        const n = Number(e.target.value);
                        if (Number.isFinite(n)) change(row.key, row.kind === "mib" ? Math.round(n) * MIB : Math.round(n));
                      }}
                      inputProps={{ min, max, step: 1 }}
                      helperText={min !== undefined && max !== undefined ? `${min} to ${max}` : undefined}
                      sx={{ width: { sm: 220 } }} />
                  ) : (
                    <Typography variant="body1" data-testid={`syslog-value-${row.key}`}>
                      {row.kind === "mib" ? describeBytes(value as number) : `${value as number} days`}
                    </Typography>
                  )}
                </Stack>
              )}
              <Typography variant="body2" color="text.secondary">
                {row.kind === "switch" ? `${(value as boolean) ? "On" : "Off"}. ` : ""}{row.explanation}
              </Typography>
            </Stack>
          );
        })}
      </Stack></CardContent></Card>

      {notice && <Typography role="status" variant="body2">{notice}</Typography>}
      {saveError && <StatePanel variant="error" announce="status" title="Not saved" body={saveError.message} code={saveError.code} />}

      {canWrite && (
        <Stack direction="row" spacing={1.5}>
          <M3Button emphasis="filled" disabled={busy || !changed} onClick={save}>{busy ? "Saving…" : "Save"}</M3Button>
          <M3Button emphasis="outlined" disabled={busy || !changed} onClick={() => { setDraft({}); setNotice(null); setSaveError(null); }}>Discard changes</M3Button>
        </Stack>
      )}

      {confirm && (
        <Dialog open onClose={() => setConfirm(null)} fullWidth maxWidth="sm" aria-labelledby="syslog-confirm-title">
          <DialogTitle id="syslog-confirm-title">{confirm.length === 1 ? confirm[0].confirm!.title : "Switch on both collections?"}</DialogTitle>
          <DialogContent>
            <Stack spacing={2}>
              {confirm.map((row) => (
                <Box key={row.key}>
                  <Typography variant="subtitle2">{row.label}: what starts happening</Typography>
                  <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
                    {row.confirm!.starts.map((line) => <Typography component="li" variant="body2" key={line}>{line}</Typography>)}
                  </Box>
                </Box>
              ))}
            </Stack>
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="text" onClick={() => setConfirm(null)}>Cancel</M3Button>
            <M3Button emphasis="filled" onClick={() => void persist()}>Switch on and save</M3Button>
          </DialogActions>
        </Dialog>
      )}
    </Stack>
  );
}
