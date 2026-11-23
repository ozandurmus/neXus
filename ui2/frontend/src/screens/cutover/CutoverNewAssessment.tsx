import { useEffect, useMemo, useRef, useState } from "react";
import {
  Alert, Box, Button, Checkbox, CircularProgress, FormControlLabel, Link, MenuItem, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from "@mui/material";

import { listDevices, listPolicies, runCutoverAssessment, type CutoverSpec, type DeviceSummary, type PolicyMetadata } from "../../auth/adminApi";
import { m3 } from "../../theme/m3Theme";
import { detectMapping, parsePreview, required, type CsvField } from "../offlineCsv";
import { cutoverFailureText, isTopologyNotBuilt } from "./cutoverErrors";

/** The columns an assessment reads; the SIEM rule column of Rule object usage has no meaning here. */
export const CUTOVER_FIELDS: readonly Exclude<CsvField, "rule">[] = ["source", "destination", "port", "protocol", "time", "count", "action", "sourceZone", "destinationZone"];
const LABELS: Record<Exclude<CsvField, "rule">, string> = {
  source: "Source IP", destination: "Destination IP", port: "Destination port", protocol: "Protocol", time: "Time", count: "Count",
  action: "Action", sourceZone: "Source zone", destinationZone: "Destination zone",
};
export const MAX_FILES = 8;
const MAX_BYTES = 100_000_000;
/** Vendors whose stored policy the lookup can evaluate; any other enrolled firewall is NOT_EVALUABLE. */
export const LOOKUP_VENDOR_HINTS = new Set(["check_point", "palo_alto", "cisco_asa", "fortinet"]);
/** What the zone inputs name per vendor hint: a Palo Alto zone, a Cisco ASA interface nameif, a FortiGate interface or zone. Check Point rules need none. */
export const ZONE_NOUNS: Readonly<Record<string, string>> = { palo_alto: "zone", cisco_asa: "interface (nameif)", fortinet: "interface or zone" };
export const zoneNoun = (vendorHint: string | undefined): string | undefined => (vendorHint === undefined ? undefined : ZONE_NOUNS[vendorHint]);

interface Upload { readonly file: File; readonly headers: string[]; readonly rows: string[][]; mapping: Partial<Record<CsvField, string>> }

export const mappingValid = (mapping: Partial<Record<CsvField, string>>) => {
  const used = Object.values(mapping).filter(Boolean);
  return required.every((f) => mapping[f]) && new Set(used).size === used.length;
};

/**
 * The New assessment panel (brief, screen 2). By default no firewall is picked: every flow's target comes from its
 * computed path. The override is an explicit operator choice, off by default, and is labelled on every result.
 */
export function CutoverNewAssessment({ segmentId, onDone }: { readonly segmentId: string; readonly onDone: (assessmentId: string) => void }) {
  const [devices, setDevices] = useState<DeviceSummary[]>([]), [policies, setPolicies] = useState<PolicyMetadata[]>([]);
  const [override, setOverride] = useState(false);
  const [deviceId, setDeviceId] = useState(""), [policyId, setPolicyId] = useState(""), [context, setContext] = useState("");
  const [segmentZone, setSegmentZone] = useState(""), [peerZone, setPeerZone] = useState("");
  const [oldPolicyId, setOldPolicyId] = useState(""), [oldTarget, setOldTarget] = useState("");
  const [uploads, setUploads] = useState<Upload[]>([]), [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(""), [topologyMissing, setTopologyMissing] = useState(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  useEffect(() => {
    let active = true;
    listDevices().then((r) => { if (active) setDevices(r.devices ?? []); }).catch(() => { if (active) setDevices([]); });
    listPolicies().then((r) => { if (active) setPolicies(r.policies ?? []); }).catch(() => { if (active) setPolicies([]); });
    return () => { active = false; };
  }, []);

  const device = devices.find((d) => d.device_id === deviceId);
  const evaluable = device ? LOOKUP_VENDOR_HINTS.has(device.vendor_hint) : false;
  const zoneName = zoneNoun(device?.vendor_hint);
  const targetPolicies = useMemo(() => policies.filter((p) => (p.targets ?? []).some((t) => t.deviceId === deviceId)), [policies, deviceId]);
  const contexts = useMemo(() => [...new Set((policies.find((p) => p.id === policyId)?.targets ?? []).filter((t) => t.deviceId === deviceId).map((t) => t.context ?? ""))],
    [policies, policyId, deviceId]);
  const oldPolicy = policies.find((p) => p.id === oldPolicyId);
  const oldTargets = oldPolicy?.targets ?? [];

  const overrideComplete = !override || (Boolean(deviceId) && (!evaluable || Boolean(policyId)) && (zoneName === undefined || (segmentZone.trim() !== "" && peerZone.trim() !== "")));
  const filesComplete = uploads.length > 0 && uploads.every((u) => mappingValid(u.mapping));
  const canRun = overrideComplete && filesComplete && confirmed && !busy;

  const addFiles = async (list: FileList | null) => {
    setError("");
    const chosen = [...(list ?? [])];
    if (uploads.length + chosen.length > MAX_FILES) { setError(`At most ${MAX_FILES} files per assessment.`); return; }
    const added: Upload[] = [];
    for (const file of chosen) {
      if (!file.name.toLowerCase().endsWith(".csv") || !file.size || file.size > MAX_BYTES) { setError(`${file.name}: select a nonempty .csv file, maximum 100 MB.`); return; }
      try {
        const prefix = file.slice(0, 21 * 65536);
        const parsed = parsePreview(await prefix.text(), prefix.size === file.size);
        added.push({ file, headers: parsed.headers, rows: parsed.rows, mapping: detectMapping(parsed.headers) });
      } catch { setError(`${file.name}: CSV preview failed. Use UTF-8, commas and valid quoting; records must not exceed 65536 characters.`); return; }
    }
    if (mounted.current) { setUploads((current) => [...current, ...added]); setConfirmed(false); }
  };
  const setMapping = (index: number, field: CsvField, column: string) => {
    setUploads((current) => current.map((u, i) => (i === index ? { ...u, mapping: { ...u.mapping, [field]: column } } : u))); setConfirmed(false);
  };

  const run = async () => {
    if (!canRun) return;
    setBusy(true); setError(""); setTopologyMissing(false);
    const spec: CutoverSpec = {
      mappings: uploads.map((u) => Object.fromEntries(Object.entries(u.mapping).filter(([field, column]) => column && field !== "rule")) as Record<string, string>),
    };
    if (override) spec.targetOverride = { deviceId, ...(evaluable ? { policyId, context } : {}), segmentZone: segmentZone.trim(), peerZone: peerZone.trim() };
    if (oldPolicyId) {
      spec.oldPolicyId = oldPolicyId;
      const old = oldTargets[Number(oldTarget)];
      if (old) { spec.oldDeviceId = old.deviceId; spec.oldContext = old.context ?? ""; }
    }
    try {
      const result = await runCutoverAssessment(segmentId, spec, uploads.map((u) => u.file));
      if (mounted.current) onDone(result.assessmentId);
    } catch (failure) {
      if (!mounted.current) return;
      setTopologyMissing(isTopologyNotBuilt(failure));
      const status = (failure as { status?: number }).status;
      setError(status === 429 ? "Another assessment is running. Retry when it completes." : cutoverFailureText(failure, "The assessment was refused. Check the files and the column mappings."));
    } finally { if (mounted.current) setBusy(false); }
  };

  return <Box component="section" aria-label="New assessment" sx={{ mt: 3, border: `1px solid ${m3.outlineVar}`, borderRadius: 1, p: 2, display: "flex", flexDirection: "column", gap: 2 }}>
    <Typography variant="subtitle1" component="h3">New assessment</Typography>

    <Box data-fact="target-mode">
      {override ? <Typography sx={{ fontWeight: 600 }}>Target firewall: OPERATOR OVERRIDE. Every flow is checked against the firewall you name below, whatever its path.</Typography>
        : <>
          <Typography sx={{ fontWeight: 600 }}>Target firewalls: from each flow's computed path</Typography>
          <Typography variant="body2">Each flow is traced through the held topology and checked against the stored policy of the firewalls on its path.
            The fabric redirect (contracts, service graph / PBR) is not modelled yet, so these are today's routed paths, not the redirected ones.</Typography>
        </>}
      <FormControlLabel sx={{ mt: 1 }} control={<Switch checked={override} disabled={busy} onChange={(e) => setOverride(e.target.checked)} />} label="Override target" />
    </Box>

    {override && <Stack gap={2} data-fact="override-fields" sx={{ maxWidth: 640 }}>
      <TextField select label="Target firewall" value={deviceId} required disabled={busy}
        onChange={(e) => { setDeviceId(e.target.value); setPolicyId(""); setContext(""); }}>
        <MenuItem value="" disabled>Select the firewall</MenuItem>
        {devices.map((d) => <MenuItem key={d.device_id} value={d.device_id}>{d.hostname || d.device_id} · {d.vendor_hint}</MenuItem>)}
      </TextField>
      {deviceId && !evaluable && <Alert severity="info" data-fact="not-collected">Policy not collected yet for this firewall's vendor: every flow will be NOT_EVALUABLE.</Alert>}
      {deviceId && evaluable && <>
        <TextField select label="Stored policy installed on it" value={policyId} required disabled={busy}
          helperText={targetPolicies.length === 0 ? "No stored policy names this firewall as a target. Collect its management source from Policy first." : "Only policies that name this firewall are listed"}
          onChange={(e) => { setPolicyId(e.target.value); setContext(""); }}>
          <MenuItem value="" disabled>Select the policy</MenuItem>
          {targetPolicies.map((p) => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
        </TextField>
        {contexts.length > 1 && <TextField select label="Context" value={context} disabled={busy} onChange={(e) => setContext(e.target.value)}>
          <MenuItem value="">Any context of this firewall</MenuItem>
          {contexts.map((c) => <MenuItem key={c} value={c}>{c || "default"}</MenuItem>)}
        </TextField>}
      </>}
      {zoneName !== undefined && <>
        <Typography variant="body2">This firewall's rules match on the {zoneName} the traffic arrives in and leaves by. Name the {zoneName} that faces the segment
          and the one that faces its peers; without both, every flow stays not decided.</Typography>
        <Stack direction={{ xs: "column", sm: "row" }} gap={2}>
          <TextField label={`Segment-side ${zoneName}`} value={segmentZone} required disabled={busy} onChange={(e) => setSegmentZone(e.target.value)} inputProps={{ maxLength: 200 }} />
          <TextField label={`Peer-side ${zoneName}`} value={peerZone} required disabled={busy} onChange={(e) => setPeerZone(e.target.value)} inputProps={{ maxLength: 200 }} />
        </Stack>
      </>}
    </Stack>}

    <Stack gap={2} sx={{ maxWidth: 640 }} data-fact="old-path">
      <TextField select label="Old-path policy (optional)" value={oldPolicyId} disabled={busy}
        helperText="The policy of the firewall the segment's traffic crosses today; each flow then also says what the old path decided"
        onChange={(e) => { setOldPolicyId(e.target.value); setOldTarget(""); }}>
        <MenuItem value="">None</MenuItem>
        {policies.map((p) => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
      </TextField>
      {oldPolicy && oldTargets.length > 0 && <TextField select label="Old-path firewall (optional)" value={oldTarget} disabled={busy} onChange={(e) => setOldTarget(e.target.value)}>
        <MenuItem value="">Any target of this policy</MenuItem>
        {oldTargets.map((t, i) => <MenuItem key={`${t.deviceId}:${t.context}`} value={String(i)}>{t.name || t.deviceId}{t.context ? ` · ${t.context}` : ""}</MenuItem>)}
      </TextField>}
    </Stack>

    <Box>
      <Typography variant="subtitle2">Traffic-log files of the segment</Typography>
      <Typography variant="caption" display="block">Up to {MAX_FILES} UTF-8 CSV files, 100 MB each, one column mapping per file. Files and raw rows are never saved; only the aggregated flows are.</Typography>
      <Button component="label" disabled={busy || uploads.length >= MAX_FILES} sx={{ mt: 1 }}>Add CSV files
        <input aria-label="Traffic-log CSV files" type="file" accept=".csv,text/csv" multiple hidden onChange={(e) => { void addFiles(e.target.files); e.target.value = ""; }} />
      </Button>
      {uploads.map((u, index) => <Box key={`${u.file.name}:${index}`} data-upload={index} sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: 1, p: 1.5, mt: 1.5 }}>
        <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
          <Typography sx={{ fontWeight: 600 }}>File {index + 1} · {u.file.name}</Typography>
          <Button size="small" disabled={busy} onClick={() => { setUploads((c) => c.filter((_, i) => i !== index)); setConfirmed(false); }}>Remove</Button>
          {!mappingValid(u.mapping) && <Typography variant="caption" sx={{ color: m3.error }}>Map source, destination, port and protocol, each to a different column.</Typography>}
        </Stack>
        <Stack direction="row" gap={1} flexWrap="wrap" sx={{ my: 1.5 }}>
          {CUTOVER_FIELDS.map((field) => <TextField key={field} select size="small" label={LABELS[field]} value={u.mapping[field] ?? ""} required={required.includes(field)} disabled={busy}
            sx={{ minWidth: 170 }} onChange={(e) => setMapping(index, field, e.target.value)}>
            <MenuItem value="">{required.includes(field) ? "Select column" : "Not mapped"}</MenuItem>
            {u.headers.map((h) => <MenuItem key={h} value={h}>{h}</MenuItem>)}
          </TextField>)}
        </Stack>
        <Box component="details"><Box component="summary" sx={{ cursor: "pointer" }}>Preview, first {u.rows.length} rows</Box>
          <Box sx={{ overflow: "auto", maxHeight: 240 }}><Table size="small" aria-label={`Preview of file ${index + 1}`}>
            <TableHead><TableRow>{u.headers.map((h) => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
            <TableBody>{u.rows.map((row, i) => <TableRow key={i}>{row.map((v, j) => <TableCell key={j}>{v}</TableCell>)}</TableRow>)}</TableBody>
          </Table></Box>
        </Box>
      </Box>)}
      <Typography variant="caption" display="block" sx={{ mt: 1 }}>Header detection is a suggestion. Action is read only to flag flows already dropped today; it never decides the target verdict.</Typography>
    </Box>

    <FormControlLabel control={<Checkbox checked={confirmed} disabled={busy || !filesComplete} onChange={(e) => setConfirmed(e.target.checked)} />}
      label="I confirm the column mappings and that these logs are the segment's traffic on its current path" />
    {error && <Alert severity="error" data-fact="assessment-error">{error}{topologyMissing && <> <Link href="?screen=trace&mode=path">Open Trace to rebuild the topology</Link>, or name a target override.</>}</Alert>}
    <Stack direction="row" gap={1} alignItems="center">
      <Button variant="contained" disabled={!canRun} onClick={() => void run()}>Run assessment</Button>
      {busy && <Stack direction="row" gap={1} role="status" alignItems="center"><CircularProgress size={18} /><Typography>Assessing… large files may take several minutes.</Typography></Stack>}
    </Stack>
  </Box>;
}
