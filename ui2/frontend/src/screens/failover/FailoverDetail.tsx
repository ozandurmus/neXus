import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControlLabel from "@mui/material/FormControlLabel";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import type { CpFailoverSummary, CpFailoverUnit } from "../../auth/adminApi";
import { PRODUCT_NAME } from "../../brand/product";
import { M3Button } from "../../shell/M3Widgets";
import { clusterModeWord, readinessReasonLabel, statusWord } from "../../shell/presenters";
import { formatStamp, relativeAge } from "../../shell/time";
import { m3 } from "../../theme/m3Theme";
import { FailoverIncidentsPanel, type SharedIncidents } from "../FailoverIncidentsPanel";
import { PanConfigSyncAction } from "../PanConfigSyncAction";
import { readinessWarningChecks, readyWord } from "../readinessWarnings";
import { isActiveRole, isStandbyRole, observedRoles, readinessStopReason } from "../ReadinessChecksTable";
import { ChecksByConsequence } from "./ChecksByConsequence";
import { FailoverHistory } from "./FailoverHistory";
import { FailoverRunView } from "./FailoverRunView";
import { ALL_VIRTUAL_SYSTEMS_TOOLTIP, allVirtualSystemsLabel, chassisRollup, failoverButtonLabel, failoverVendorLabel, reasonLine } from "./failoverRowModel";
import { DEFAULT_VALIDITY_MINUTES, VALIDITY_MINUTES, WORK_LOG_NOTE_LABEL, WORK_LOG_NOTE_MAX, recoveryLabel } from "./failoverShared";
import { useFailoverUnit } from "./useFailoverUnit";

/** What the dialog and the button call the unit. */
const unitLabel = (unit: CpFailoverUnit) => unit.maskedName ?? unit.virtual_system ?? unit.cluster_member_ref;
const isVirtualSystem = (unit: CpFailoverUnit) => unit.unitKind === "VIRTUAL_SYSTEM" || (!unit.unitKind && unit.unitId !== unit.clusterId);

/**
 * The one detail surface of a cluster (REDESIGN section 4): header (name, roles, verdict, Re-check, the failover button and the
 * server's refusal), then Now, the checks by consequence, the virtual systems (a chassis) and the history. Everything the server
 * decides is rendered as sent; nothing here decides who may start or approve.
 */
export function FailoverDetail({ unit, units, singleAdmin, summaryRows = [], incidents, onSelectUnit, onRefreshUnits }: {
  unit: CpFailoverUnit; units: CpFailoverUnit[]; singleAdmin: boolean | null | undefined; summaryRows?: readonly CpFailoverSummary[];
  incidents?: SharedIncidents; onSelectUnit: (unitId: string) => void; onRefreshUnits: () => void;
}) {
  const uf = useFailoverUnit({ unit, shown: true, singleAdmin, onRefreshUnits });
  const label = unitLabel(unit);
  const summary = summaryRows.find(row => row.unitId === unit.unitId);
  const chassis = unit.unitKind === "VSX_CHASSIS";
  const virtualSystems = units.filter(isVirtualSystem);
  const parent = isVirtualSystem(unit) ? units.find(u => u.unitKind === "VSX_CHASSIS") : undefined;
  const allVirtualSystems = uf.allVirtualSystems;
  const failoverLabel = failoverButtonLabel(unit);
  const startLabel = allVirtualSystems ? "Fail over all virtual systems" : chassis ? "Fail over VS0" : "Start failover";
  const sequence = unit.sequence;
  const preChecks = uf.readinessRun?.checks.filter(c => c.phase === "pre");
  // The latest readiness run once it is read; the summary row's checks while it loads (one list, never two).
  const checks = preChecks ?? summary?.readiness?.checks ?? [];
  const checkedAt = uf.readinessRun?.checks.map(c => c.observedAt).sort().at(-1) ?? summary?.readiness?.observedAt ?? unit.readinessObservedAt;
  const outcome = uf.readinessActive ? "RUNNING" : uf.readinessRun?.outcome ?? summary?.readiness?.status ?? null;
  const verdictWord = outcome === "RUNNING" ? "Running pre-checks" : outcome === "READY" ? readyWord(readinessWarningChecks(checks).length) : outcome === "NOT_READY" ? "Not ready" : statusWord("UNKNOWN");
  const verdictColors = outcome === "READY" ? readinessWarningChecks(checks).length ? [m3.warningContainer, m3.onWarningContainer] : [m3.successContainer, m3.onSuccessContainer] : outcome === "NOT_READY" ? [m3.errorContainer, m3.onErrorContainer] : [m3.scHigh, m3.onSurfaceVar];
  const roles = observedRoles(checks, unit.members ?? [], checkedAt, unit.vendor);
  const roleName = (match: (role: string | null | undefined) => boolean) => (unit.members ?? [])
    .filter(m => match(roles.get(m.device_id)?.role ?? m.ha_role)).map(m => m.hostname ?? "Unknown member").join(", ");
  const active = roleName(isActiveRole), standby = roleName(isStandbyRole);
  const stop = readinessStopReason(uf.readinessRun?.message);
  const vsRows = virtualSystems.map(vs => summaryRows.find(row => row.unitId === vs.unitId)).filter((row): row is CpFailoverSummary => Boolean(row));
  const dialogOpen = uf.dialog;
  return <Stack spacing={2.5} aria-label={`Failover detail ${label}`} data-unit-kind={unit.unitKind ?? (unit.unitId === unit.clusterId ? "CLUSTER" : "VIRTUAL_SYSTEM")}>
    <Box component="header" data-failover-detail-header sx={{ display: "grid", gap: 0.75 }}>
      {parent && <Box><Button size="small" onClick={() => onSelectUnit(parent.unitId)} sx={{ p: 0, textTransform: "none" }}>← {unitLabel(parent)}</Button></Box>}
      <Box sx={{ display: "flex", gap: 1.5, alignItems: "baseline", flexWrap: "wrap" }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 600 }}>{chassis ? `VSX chassis · ${label}` : isVirtualSystem(unit) ? `Virtual System · ${label}` : label}</Typography>
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{failoverVendorLabel(unit.vendor)} · Mode: <span title={unit.mode ?? undefined}>{clusterModeWord(unit.mode)}</span>{!unit.vendor || unit.vendor === "check_point" ? ` · Recovery mode: ${recoveryLabel(unit.recoveryMode)}` : ""}</Typography>
      </Box>
      <Typography variant="body2">{active || standby ? <>Active: {active || "Not observed"} <span aria-hidden>→</span> Standby: {standby || "Not observed"}</> : "Roles not observed"}</Typography>
      <Box sx={{ display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap" }}>
        <Chip size="small" label={verdictWord} data-detail-verdict sx={{ bgcolor: verdictColors[0], color: verdictColors[1] }} />
        {unit.masked && <Chip size="small" label="AIView Pseudonymized" sx={{ bgcolor: m3.sc, color: m3.onSurfaceVar }} />}
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{checkedAt ? `checked ${relativeAge(checkedAt, new Date())}` : "Not evaluated"}</Typography>
        <Box sx={{ ml: "auto", display: "flex", gap: 1 }}>
          {uf.canRunChecks && <M3Button deviceJob emphasis="outlined" disabled={uf.readinessActive || !uf.idle} onClick={uf.runChecks}>Re-check</M3Button>}
          {uf.failoverOffered && <M3Button deviceJob emphasis="filled" disabled={uf.busy || uf.armed || uf.vs0Refused
            || !uf.idle || Boolean(uf.pending)} onClick={() => uf.requestFailover("FAILOVER")}>{failoverLabel}</M3Button>}
          {uf.sequenceOffered && <Box component="span" data-failover-action="FAILOVER_ALL_VIRTUAL_SYSTEMS" title={ALL_VIRTUAL_SYSTEMS_TOOLTIP}>
            <M3Button deviceJob emphasis="outlined" disabled={uf.busy || uf.armed || uf.sequenceRefused || !uf.idle || Boolean(uf.pending)}
              onClick={() => uf.requestFailover("FAILOVER_ALL_VIRTUAL_SYSTEMS")}>{allVirtualSystemsLabel(virtualSystems.length)}</M3Button></Box>}
        </Box>
      </Box>
      {unit.refusalReason && <Typography color="error">{unit.refusalReason}</Typography>}
      {uf.executionDisabled
        ? <>
          <Typography variant="body2" data-failover-execution-disabled title={unit.failoverRefusal ?? "FAILOVER_VENDOR_NOT_AVAILABLE"}>
            Failover execution not enabled for {failoverVendorLabel(unit.vendor)}</Typography>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>{PRODUCT_NAME} does not execute failover for {failoverVendorLabel(unit.vendor)}. The checks below describe the pair's state; fail over on the device. · {unit.failoverRefusal ?? "FAILOVER_VENDOR_NOT_AVAILABLE"}</Typography></>
        : unit.failoverRefusal && <Typography variant="body2" data-failover-refusal title={unit.failoverRefusal}>
            {readinessReasonLabel(unit.failoverRefusal)}: its pre-checks run here; each Virtual System below is failed over on its own.</Typography>}
      {chassis && unit.chassisAction && <Typography variant="body2" data-chassis-action={unit.chassisAction}>
        VS0 is its own failover unit{unit.chassisMode === "HA" || unit.chassisMode === "VSLS" ? ` (${unit.chassisMode === "HA" ? "VSX HA" : "VSLS"} mode)` : ""}: it
        is checked and confirmed on VS0 alone; no virtual system has to move with it. Each Virtual System below is failed over on its own.
        {unit.chassisStartRefusal && <Box component="span" data-chassis-start-refusal title={unit.chassisStartRefusal}> VS0 not now: {readinessReasonLabel(unit.chassisStartRefusal)}.</Box>}</Typography>}
      {uf.sequenceOffered && unit.sequenceStartRefusal && <Typography variant="body2" data-sequence-start-refusal title={unit.sequenceStartRefusal}>
        All virtual systems not now: {readinessReasonLabel(unit.sequenceStartRefusal)}.</Typography>}
    </Box>
    <Stack spacing={1.5} data-failover-now>
      <FailoverIncidentsPanel clusterId={unit.clusterId} shared={incidents} onReleased={() => { onRefreshUnits(); void uf.refresh(); }} />
      {uf.pending && <Typography role="status">Awaiting second approval</Typography>}
      {unit.masked !== true && unit.canApprove && !unit.failoverRefusal && uf.pending?.canApprove && <M3Button emphasis="outlined" disabled={!uf.idle}
        onClick={uf.secondApprove}>Approve request revision {uf.pending.revision}</M3Button>}
      {sequence && <Typography variant="body2" role="status" data-sequence-state={sequence.state}>
        All virtual systems: {sequence.state === "RUNNING" ? `virtual system ${sequence.started} of ${sequence.total} running`
          : `${readinessReasonLabel(sequence.outcome ?? sequence.state)}${sequence.failedVsId ? ` (VSID ${sequence.failedVsId})` : ""}`}</Typography>}
      {uf.run && <FailoverRunView run={uf.run} unit={unit} onChanged={() => { onRefreshUnits(); void uf.refresh(); }} />}
      {uf.error && !dialogOpen && <Stack direction="row" spacing={1} alignItems="center"><Typography role="alert" color="error">{uf.error}</Typography>
        {uf.canRetry && <M3Button emphasis="outlined" disabled={uf.busy} onClick={uf.retry}>Retry</M3Button>}</Stack>}
    </Stack>
    <Box component="section" aria-label="Checks">
      <Typography variant="subtitle2" sx={{ mb: 0.5 }}>Checks{uf.readinessActive && <CircularProgress size={14} aria-label="Running pre-checks" sx={{ ml: 1 }} />}</Typography>
      {!uf.readinessActive && stop && <Typography variant="body2" sx={{ mb: 1 }}>{stop}</Typography>}
      <ChecksByConsequence checks={checks} members={unit.members ?? []} vendor={unit.vendor} observedAt={checkedAt}
        rowAction={checkNo => checkNo === 4 && unit.vendor === "palo_alto"
          ? <PanConfigSyncAction unit={unit} onDone={() => { onRefreshUnits(); void uf.refresh(); }}
            onRecheck={uf.canRunChecks ? uf.runChecks : undefined} recheckBusy={uf.readinessActive || !uf.idle} recheckRunning={uf.readinessActive} /> : null} />
      <Typography variant="caption" sx={{ display: "block", color: m3.onSurfaceVar, mt: 1 }}>Failover runs re-check everything at start.</Typography>
    </Box>
    {!isVirtualSystem(unit) && virtualSystems.length > 0 && <Box component="section" aria-label="Virtual systems">
      <Typography variant="subtitle2">Virtual systems · {vsRows.length === virtualSystems.length ? chassisRollup(vsRows) : `${virtualSystems.length} virtual systems`}</Typography>
      {virtualSystems.map(vs => {
        const row = summaryRows.find(r => r.unitId === vs.unitId);
        const status = row?.readiness?.status ?? "UNKNOWN";
        const reason = reasonLine(row, vs.members ?? []);
        return <Box key={vs.unitId} data-vs-row={vs.unitId} sx={{ display: "grid", gridTemplateColumns: "100px 1fr 110px", gap: 1.5, py: 0.75, borderTop: `1px solid ${m3.outlineVar}`, alignItems: "center" }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>{unitLabel(vs)}</Typography>
          <Typography variant="body2"><Chip size="small" label={status === "READY" ? "Ready" : status === "NOT_READY" ? "Not ready" : statusWord("UNKNOWN")}
            sx={{ mr: 1, bgcolor: status === "READY" ? m3.successContainer : status === "NOT_READY" ? m3.errorContainer : m3.scHigh,
              color: status === "READY" ? m3.onSuccessContainer : status === "NOT_READY" ? m3.onErrorContainer : m3.onSurfaceVar }} />
            {row ? <>{reason.title ? `${reason.title} – ` : ""}{reason.headline}{reason.where ? ` – ${reason.where.text}` : ""}</> : "Readiness not read"}</Typography>
          <Button size="small" aria-label={`Open Virtual System · ${unitLabel(vs)}`} onClick={() => onSelectUnit(vs.unitId)} sx={{ textTransform: "none" }}>Open</Button>
        </Box>;
      })}
    </Box>}
    <FailoverHistory runs={uf.runs} unit={unit} />
    <Dialog open={uf.dialog} onClose={() => { if (!uf.busy) uf.setDialog(false); }}>
      <DialogTitle>{startLabel}</DialogTitle>
      <DialogContent><Stack spacing={2} sx={{ pt: 1 }} data-failover-policy={uf.single ? "single" : singleAdmin === false ? "two-person" : "unknown"}>
        <Typography>Target: {label}</Typography>
        <Box data-readiness-verdict={uf.verdict.tone}>
          <Typography sx={{ fontWeight: 600, color: uf.verdict.tone === "ready" ? m3.onSurface : uf.verdict.tone === "blocked" ? m3.error : m3.onSurfaceVar }}>
            Readiness: {uf.verdict.text}{uf.verdict.tone !== "ready" && uf.verdict.detail ? ` · ${uf.verdict.detail}` : ""}</Typography>
          <Typography variant="caption">Pre-checks read: {uf.readinessAt ? `${relativeAge(uf.readinessAt, new Date())} (${formatStamp(uf.readinessAt)})` : "not evaluated"}. The run repeats every pre-check before it switches.</Typography>
        </Box>
        {allVirtualSystems && <Typography data-sequence-warning>Every virtual system of this chassis is failed over one after another (never in parallel: one failover write at a time across the fleet), in VSID order, each in its own run with its own pre-checks, switch, post-checks and return. VS0 is not part of it. The sequence stops at the first virtual system that does not succeed.</Typography>}
        {chassis && !allVirtualSystems && <Typography data-vs0-scope>VS0 fails over on its own. It is checked and confirmed on VS0 alone; the virtual systems are separate units and do not have to move with it.</Typography>}
        <Typography>Intended role swap: current active becomes DOWN/suspended; current standby/passive becomes active. The former active is then returned to standby/passive after successful checks.</Typography>
        <Typography>Traffic may be interrupted. The run may stop with one member DOWN. Intervention may be required.</Typography>
        <Typography data-failover-flow>{uf.single ? "The failover starts as soon as you confirm. Nothing more is asked."
          : singleAdmin === false ? "A second administrator must agree before anything is sent. The failover then starts from this page; nothing more is asked of you."
          : "The failover starts as soon as you confirm, unless your role needs a second administrator to agree first. Nothing more is asked of you."}</Typography>
        <TextField label={WORK_LOG_NOTE_LABEL} required autoFocus value={uf.noteText} disabled={Boolean(uf.draft)} placeholder="e.g. change CHG-123 planned failover"
          helperText="Recorded with the run." inputProps={{ maxLength: WORK_LOG_NOTE_MAX }} onChange={e => uf.setNote(e.target.value)}
          onKeyDown={e => { if (e.key === "Enter" && !uf.busy && !uf.refused && uf.noteText.trim()) { e.preventDefault(); void uf.confirm(); } }} />
        {uf.draft ? <Typography variant="caption" data-failover-validity>This request was already made. Valid until {formatStamp(uf.draft.until)}.</Typography>
          : uf.single ? <Typography variant="caption" data-failover-validity>Valid for {DEFAULT_VALIDITY_MINUTES} minutes.</Typography>
          : <Box><Typography variant="caption" id={`validity-${unit.unitId}`}>Request valid for</Typography>
            <RadioGroup row aria-labelledby={`validity-${unit.unitId}`} value={String(uf.minutes)} onChange={e => uf.setMinutes(Number(e.target.value))}>
              {VALIDITY_MINUTES.map(value => <FormControlLabel key={value} value={String(value)} control={<Radio size="small" />} label={`${value} minutes`} />)}
            </RadioGroup></Box>}
        {uf.error && <Stack direction="row" spacing={1} alignItems="center"><Typography role="alert" color="error">{uf.error}</Typography>
          {uf.canRetry && <M3Button emphasis="outlined" disabled={uf.busy} onClick={uf.retry}>Retry</M3Button>}</Stack>}
      </Stack></DialogContent>
      <DialogActions><M3Button emphasis="text" disabled={uf.busy} onClick={() => uf.setDialog(false)}>Cancel</M3Button>
        <M3Button deviceJob emphasis="filled" disabled={uf.busy || uf.refused || !uf.noteText.trim()} onClick={() => void uf.confirm()}>{uf.single ? "Fail over now" : "Request failover"}</M3Button></DialogActions>
    </Dialog>
  </Stack>;
}
