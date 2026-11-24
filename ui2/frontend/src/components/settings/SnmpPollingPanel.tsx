import { useCallback, useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
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
import {
  getSnmpPolling,
  listDevices,
  saveSnmpPolling,
  type ApiError,
  type SnmpCredentialOption,
  type SnmpDeviceRow,
  type SnmpPollingPatch,
  type SnmpPollingView,
  type SnmpScopeMode,
  type SnmpVendorRow,
} from "../../auth/adminApi";

const VENDOR_LABEL: Readonly<Record<string, string>> = { check_point: "Check Point", palo_alto: "Palo Alto" };
/** The select value that means "no credential here": inherit (vendor, device) or none (fleet default). */
const INHERIT = "";

/** What turning the fleet switch on starts, said before it happens. */
export const SNMP_SWITCH_ON_STARTS: readonly string[] = [
  "Within 15 seconds the worker starts polling every Check Point and Palo Alto gateway in scope over SNMPv3 (UDP 161), each device once per interval, its first poll spread across the interval.",
  "Each poll is read-only: one GET of the gated OIDs (uptime, CPU, memory, sessions) and, for Palo Alto, a bounded walk of two tables. Nothing is ever set on a device, and no SSH or API session is opened.",
  "At most 16 devices are polled at a time, 3 s timeout and one retry. A device that does not answer three polls in a row is UNKNOWN (SNMP_NO_ANSWER) and is polled every 30 minutes until it answers.",
  "Samples are kept 35 days, hourly p95 and maximum 400 days. The change is written to the audit log under your session.",
];

function problemsOf(err: unknown): { readonly message: string; readonly code: string | null } {
  const e = err as ApiError | undefined;
  const body = (e?.body ?? {}) as { problems?: string[]; message?: string; code?: string; error?: string };
  if (body.problems?.length) return { message: body.problems.join("; "), code: body.code ?? null };
  if (typeof body.message === "string") return { message: body.message, code: body.code ?? null };
  return { message: `request failed${e?.status ? ` (status ${e.status})` : ""}`, code: body.code ?? body.error ?? null };
}

function credentialLabel(c: SnmpCredentialOption): string {
  const posture = [c.security_level, c.auth_protocol, c.priv_protocol].filter(Boolean).join(" / ");
  return `${c.display_name}${posture ? ` (${posture})` : ""}${c.suitable ? "" : " (not used: needs authPriv, SHA-2 and AES)"}`;
}

interface Draft {
  readonly enabled: boolean;
  readonly interval_s: number;
  readonly scope_mode: SnmpScopeMode;
  readonly default_credential_reference_id: string | null;
  readonly vendors: readonly SnmpVendorRow[];
  readonly devices: readonly SnmpDeviceRow[];
}

function draftOf(v: SnmpPollingView): Draft {
  const vendors = v.pilot_vendors.map((vendor) => v.vendors.find((r) => r.vendor === vendor)
    ?? { vendor, in_scope: false, credential_reference_id: null });
  return {
    enabled: v.enabled, interval_s: v.interval_s, scope_mode: v.scope_mode,
    default_credential_reference_id: v.default_credential_reference_id, vendors, devices: v.devices,
  };
}

/** Only what differs from the stored view; lists are sent whole when any row in them changed. */
export function snmpPatch(view: SnmpPollingView, d: Draft): SnmpPollingPatch {
  const stored = draftOf(view);
  const patch: { -readonly [K in keyof SnmpPollingPatch]: SnmpPollingPatch[K] } = {};
  if (d.enabled !== stored.enabled) patch.enabled = d.enabled;
  if (d.interval_s !== stored.interval_s) patch.interval_s = d.interval_s;
  if (d.scope_mode !== stored.scope_mode) patch.scope_mode = d.scope_mode;
  if (d.default_credential_reference_id !== stored.default_credential_reference_id) {
    patch.default_credential_reference_id = d.default_credential_reference_id;
  }
  if (JSON.stringify(d.vendors) !== JSON.stringify(stored.vendors)) patch.vendors = d.vendors;
  if (JSON.stringify(d.devices) !== JSON.stringify(stored.devices)) patch.devices = d.devices;
  return patch;
}

/**
 * Administration > SNMP polling (SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 11, option B): the fleet switch (off
 * until a Security Admin turns it on), the interval, the fleet default SNMPv3 credential from the credential store, and
 * the scope with per-vendor and per-device rows. Edit controls appear only when the server says this session may write;
 * the aiview persona reads a masked, read-only view. Turning the switch on goes through a confirm step.
 */
export function SnmpPollingPanel() {
  const [view, setView] = useState<SnmpPollingView | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [names, setNames] = useState<Readonly<Record<string, string>>>({});
  const [restricted, setRestricted] = useState(false);
  const [loadError, setLoadError] = useState<{ message: string; code: string | null } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<{ message: string; code: string | null } | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [adding, setAdding] = useState(INHERIT);

  const load = useCallback(() => {
    setLoadError(null);
    getSnmpPolling().then((v) => { setView(v); setDraft(draftOf(v)); }).catch((e) => {
      if (isRestricted(e)) { setRestricted(true); return; }
      setLoadError(problemsOf(e));
    });
    // Device names for the device rows; without them the opaque id is shown.
    listDevices().then((r) => setNames(Object.fromEntries(r.devices.map((d) => [d.device_id, d.hostname ?? d.device_id]))))
      .catch(() => setNames({}));
  }, []);
  useEffect(() => { load(); }, [load]);

  const patch = useMemo(() => (view && draft ? snmpPatch(view, draft) : {}), [view, draft]);

  if (restricted) {
    return <RestrictedPanel area="SNMP polling" role="Security Admin"
      nextStep="A Security Admin turns SNMP polling on here, after entering the SNMPv3 credential in Administration > Credentials." />;
  }
  if (loadError) {
    return <StatePanel variant="error" title="SNMP polling settings could not be read" body={loadError.message} code={loadError.code}
      action={<M3Button emphasis="outlined" onClick={load}>Retry</M3Button>} />;
  }
  if (!view || !draft) return <Typography sx={{ p: 3 }} role="status">Loading SNMP polling settings…</Typography>;

  const canWrite = view.can_write;
  const changed = Object.keys(patch).length > 0;
  const nameOf = (id: string) => names[id] ?? id;
  const credentialName = (ref: string | null) =>
    ref === null ? null : view.credentials.find((c) => c.credential_reference_id === ref)?.display_name ?? ref;
  const edit = (next: Partial<Draft>) => {
    setNotice(null);
    setSaveError(null);
    setDraft({ ...draft, ...next });
  };

  const persist = async () => {
    setConfirming(false);
    setBusy(true);
    setNotice(null);
    setSaveError(null);
    try {
      const next = await saveSnmpPolling(patch);
      setView(next);
      setDraft(draftOf(next));
      setNotice(patch.enabled === true ? "Saved. Polling starts within 15 seconds." : "Saved.");
    } catch (e) {
      setSaveError(problemsOf(e));
    } finally {
      setBusy(false);
    }
  };
  const save = () => {
    if (!changed) { setNotice("Nothing changed."); return; }
    if (patch.enabled === true) setConfirming(true);
    else void persist();
  };

  const credentialSelect = (label: string, value: string | null, emptyLabel: string, onPick: (ref: string | null) => void,
    testId: string) => canWrite ? (
    <TextField select size="small" label={label} value={value ?? INHERIT} sx={{ minWidth: 280 }}
      inputProps={{ "data-testid": testId }}
      onChange={(e) => onPick(e.target.value === INHERIT ? null : e.target.value)}>
      <MenuItem value={INHERIT}>{emptyLabel}</MenuItem>
      {view.credentials.map((c) => (
        <MenuItem key={c.credential_reference_id} value={c.credential_reference_id}>{credentialLabel(c)}</MenuItem>
      ))}
    </TextField>
  ) : (
    <Typography variant="body2" data-testid={testId}>{label}: {credentialName(value) ?? emptyLabel}</Typography>
  );

  const unlisted = view.eligible_devices.filter((d) => !draft.devices.some((r) => r.device_id === d.device_id));
  const outcomes = Object.entries(view.last_outcome_counts);

  return (
    <Stack spacing={2} sx={{ maxWidth: 960 }}>
      <Card variant="outlined"><CardContent><Stack spacing={2}>
        <Box>
          <Typography variant="h6" component="h3">SNMP polling</Typography>
          <Typography variant="body2" color="text.secondary">
            Real CPU, memory and session figures for Operations &gt; Device health, read over SNMPv3 (authPriv) instead of SSH.
            Pilot vendors: {view.pilot_vendors.map((v) => VENDOR_LABEL[v] ?? v).join(" and ")}. The SNMPv3 user is entered in
            Administration &gt; Credentials; this page only chooses which credential polling uses and where.
          </Typography>
        </Box>
        <Typography variant="caption" color="text.secondary" data-testid="snmp-last-changed">
          Last changed <Timestamp at={view.updated_at} /> by {view.updated_by ?? "UNKNOWN"}
        </Typography>
        {!canWrite && (
          <Typography role="status" variant="body2">
            Read-only: changing SNMP polling needs the Security Admin role.
          </Typography>
        )}

        <Stack spacing={0.75} sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }} data-testid="snmp-fleet-switch">
          <FormControlLabel label="Poll devices over SNMP"
            control={<Switch checked={draft.enabled} disabled={!canWrite} onChange={(e) => edit({ enabled: e.target.checked })} />} />
          <Typography variant="body2" color="text.secondary">
            {draft.enabled ? "On." : "Off."} Off: nothing is sent to any device and Device health keeps its other sources.
          </Typography>
        </Stack>

        <Stack spacing={0.75} sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }}>
          {canWrite ? (
            <TextField label="Interval (seconds)" type="number" size="small" value={draft.interval_s} sx={{ width: 220 }}
              inputProps={{ min: view.interval_min_s, max: view.interval_max_s, step: 1, "data-testid": "snmp-interval" }}
              helperText={`${view.interval_min_s} to ${view.interval_max_s}; 300 = every 5 minutes`}
              onChange={(e) => { const n = Number(e.target.value); if (Number.isFinite(n)) edit({ interval_s: Math.round(n) }); }} />
          ) : (
            <Typography variant="body1" data-testid="snmp-interval">Every {draft.interval_s} seconds</Typography>
          )}
          {credentialSelect("Fleet default credential", draft.default_credential_reference_id, "None chosen",
            (ref) => edit({ default_credential_reference_id: ref }), "snmp-default-credential")}
          {view.credentials.length === 0 && (
            <Typography variant="body2" role="status">
              No SNMPv3 credential is stored yet. Add one in Administration &gt; Credentials (kind SNMP v3, authPriv).
            </Typography>
          )}
          <Typography variant="body2" color="text.secondary">
            Retention: samples {view.raw_retention_days} days, hourly p95 and maximum {view.hourly_retention_days} days.
          </Typography>
        </Stack>

        <Stack spacing={1} sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }}>
          <Typography variant="subtitle1" component="h4">Scope</Typography>
          <RadioGroup row value={draft.scope_mode} onChange={(e) => edit({ scope_mode: e.target.value as SnmpScopeMode })}>
            <FormControlLabel value="VENDORS" disabled={!canWrite} control={<Radio />} label="By vendor" />
            <FormControlLabel value="DEVICES" disabled={!canWrite} control={<Radio />} label="Selected devices only" />
          </RadioGroup>
          <Typography variant="body2" color="text.secondary">
            By vendor: every enrolled gateway of a vendor that is on. Selected devices only: just the devices marked
            "Polled" below. A device marked "Excluded" is never polled. A credential on a device row wins over the
            vendor's, which wins over the fleet default.
          </Typography>
          {draft.vendors.map((row, i) => (
            <Stack key={row.vendor} direction={{ xs: "column", sm: "row" }} spacing={2} alignItems={{ sm: "center" }}
              data-testid={`snmp-vendor-${row.vendor}`}>
              <FormControlLabel sx={{ minWidth: 200 }} label={VENDOR_LABEL[row.vendor] ?? row.vendor}
                control={<Switch checked={row.in_scope} disabled={!canWrite || draft.scope_mode !== "VENDORS"}
                  onChange={(e) => edit({ vendors: draft.vendors.map((r, j) => j === i ? { ...r, in_scope: e.target.checked } : r) })} />} />
              {credentialSelect("Credential", row.credential_reference_id, "Fleet default",
                (ref) => edit({ vendors: draft.vendors.map((r, j) => j === i ? { ...r, credential_reference_id: ref } : r) }),
                `snmp-vendor-credential-${row.vendor}`)}
            </Stack>
          ))}
        </Stack>

        <Stack spacing={1} sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }} data-testid="snmp-device-rows">
          <Typography variant="subtitle1" component="h4">Device rows ({draft.devices.length})</Typography>
          {draft.devices.length === 0 && <Typography variant="body2" color="text.secondary">No device rows.</Typography>}
          {draft.devices.map((row, i) => (
            <Stack key={row.device_id} direction={{ xs: "column", sm: "row" }} spacing={2} alignItems={{ sm: "center" }}>
              <Typography variant="body2" sx={{ minWidth: 200 }}>{nameOf(row.device_id)}</Typography>
              {canWrite ? (
                <TextField select size="small" label="Polling" value={row.in_scope ? "in" : "out"} sx={{ minWidth: 140 }}
                  onChange={(e) => edit({ devices: draft.devices.map((r, j) => j === i ? { ...r, in_scope: e.target.value === "in" } : r) })}>
                  <MenuItem value="in">Polled</MenuItem>
                  <MenuItem value="out">Excluded</MenuItem>
                </TextField>
              ) : (
                <Typography variant="body2">{row.in_scope ? "Polled" : "Excluded"}</Typography>
              )}
              {credentialSelect("Credential", row.credential_reference_id, "Vendor or fleet default",
                (ref) => edit({ devices: draft.devices.map((r, j) => j === i ? { ...r, credential_reference_id: ref } : r) }),
                `snmp-device-credential-${i}`)}
              {canWrite && (
                <M3Button emphasis="text" onClick={() => edit({ devices: draft.devices.filter((_, j) => j !== i) })}>Remove</M3Button>
              )}
            </Stack>
          ))}
          {canWrite && unlisted.length > 0 && (
            <Stack direction="row" spacing={1.5} alignItems="center">
              <TextField select size="small" label="Add a device row" value={adding} sx={{ minWidth: 280 }}
                inputProps={{ "data-testid": "snmp-add-device" }} onChange={(e) => setAdding(e.target.value)}>
                <MenuItem value={INHERIT}>Choose a device</MenuItem>
                {unlisted.map((d) => (
                  <MenuItem key={d.device_id} value={d.device_id}>{nameOf(d.device_id)} ({VENDOR_LABEL[d.vendor] ?? d.vendor})</MenuItem>
                ))}
              </TextField>
              <M3Button emphasis="outlined" disabled={adding === INHERIT} onClick={() => {
                edit({ devices: [...draft.devices, { device_id: adding, in_scope: true, credential_reference_id: null }] });
                setAdding(INHERIT);
              }}>Add</M3Button>
            </Stack>
          )}
        </Stack>

        <Stack spacing={0.5} sx={{ pt: 1.5, borderTop: 1, borderColor: "divider" }} data-testid="snmp-last-outcomes">
          <Typography variant="subtitle1" component="h4">Last poll, past 24 hours</Typography>
          {outcomes.length === 0 ? (
            <Typography variant="body2" color="text.secondary">No poll yet.</Typography>
          ) : outcomes.map(([outcome, count]) => (
            <Typography key={outcome} variant="body2">{outcome}: {count} {count === 1 ? "device" : "devices"}</Typography>
          ))}
        </Stack>
      </Stack></CardContent></Card>

      {view.warnings.length > 0 && (
        <Box role="status" data-testid="snmp-warnings" sx={{ p: 2, border: 1, borderColor: "warning.main", borderRadius: 1 }}>
          <Typography variant="subtitle2">{view.warnings.length === 1 ? "Warning" : "Warnings"}</Typography>
          <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
            {view.warnings.map((w) => <Typography component="li" variant="body2" key={w}>{w}</Typography>)}
          </Box>
        </Box>
      )}
      {notice && <Typography role="status" variant="body2">{notice}</Typography>}
      {saveError && <StatePanel variant="error" announce="status" title="Not saved" body={saveError.message} code={saveError.code} />}

      {canWrite && (
        <Stack direction="row" spacing={1.5}>
          <M3Button emphasis="filled" disabled={busy || !changed} onClick={save}>{busy ? "Saving…" : "Save"}</M3Button>
          <M3Button emphasis="outlined" disabled={busy || !changed}
            onClick={() => { setDraft(draftOf(view)); setNotice(null); setSaveError(null); }}>Discard changes</M3Button>
        </Stack>
      )}

      {confirming && (
        <Dialog open onClose={() => setConfirming(false)} fullWidth maxWidth="sm" aria-labelledby="snmp-confirm-title">
          <DialogTitle id="snmp-confirm-title">Turn SNMP polling on?</DialogTitle>
          <DialogContent>
            <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
              {SNMP_SWITCH_ON_STARTS.map((line) => <Typography component="li" variant="body2" key={line}>{line}</Typography>)}
            </Box>
          </DialogContent>
          <DialogActions>
            <M3Button emphasis="text" onClick={() => setConfirming(false)}>Cancel</M3Button>
            <M3Button emphasis="filled" onClick={() => void persist()}>Turn on and save</M3Button>
          </DialogActions>
        </Dialog>
      )}
    </Stack>
  );
}
