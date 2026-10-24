import { useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { M3Button } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { getCpFailoverRun, getPanConfigSync, requestPanConfigSync,
  type CpFailoverUnit, type PanConfigSyncRecord, type ReadinessMember } from "../auth/adminApi";

/**
 * Palo Alto HA: "Sync configuration to passive" for a failed readiness check 4 (docs/design/PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md,
 * PO decision 2026-10-24). Shown only when the server offers it (`configSync.canRun`); the dialog names the source (active)
 * and the target (passive); the business-hours question is the shared one (adminApi `call`). Progress, the outcome and
 * the readiness re-run's Configuration sync result are shown in place.
 */
export const CONFIG_SYNC_COMMAND = "request high-availability sync-to-remote running-config";
const words: Record<string, string> = {
  CONFIG_SYNC_REQUESTED: "The active member accepted the sync command.",
  DEVICE_REPORTED_ERROR: "The active member refused the sync command (status error). Nothing is retried.",
  OUTCOME_UNKNOWN: "The sync command's outcome is uncertain. It is not resent.",
  CONFIG_SYNC_NOT_FAILED: "Configuration sync is not failing in the latest readiness.",
  READINESS_REQUIRED: "Run the pre-checks first.",
  READINESS_NOT_FRESH: "The latest pre-checks are older than 60 minutes. Run the pre-checks again first.",
  ROLES_CHANGED: "Stopped before the write: the members' roles are not the ones shown (active and passive). Nothing was sent.",
  HA_STATE_UNKNOWN: "Stopped before the write: the HA state could not be established. Nothing was sent.",
  CONFIG_ALREADY_SYNCHRONIZED: "Nothing was sent: the configuration is already synchronized.",
  CONFIG_SYNC_STATE_UNKNOWN: "Stopped before the write: the configuration sync state is unknown. Nothing was sent.",
  CONFIG_SYNC_IN_PROGRESS: "Nothing was sent: a configuration sync is already in progress on the pair.",
  CONFIG_SYNC_ALREADY_ACTIVE: "A configuration sync is already running for this unit.",
  CONFIG_SYNC_REQUEST_EXPIRED: "The request expired before it started (30 minutes). Nothing was sent.",
  MEMBER_SET_CHANGED: "The cluster members changed. Nothing was sent.",
  RUN_ALREADY_ACTIVE: "Another run is in progress on this unit.",
  OPEN_INCIDENT: "An open failover incident covers this unit or a member.",
  FAILOVER_MUTATION_DISABLED: "Failover writes are switched off.",
  MUTATION_DISABLED_BEFORE_SEND: "Failover writes were switched off before the send. Nothing was sent.",
  BUSINESS_HOURS_CONFIRM_REQUIRED: "Business hours were not confirmed. Nothing was sent.",
  WRONG_ROLE: "Only a security_admin or operation_admin can sync the configuration.",
  CONFIG_SYNC_NOT_SUPPORTED: "Configuration sync is offered for Palo Alto HA pairs only.",
};
export const configSyncText = (code?: string | null) => code ? words[code] ?? code : "";
const stageText: Record<string, string> = { QUEUED: "Queued", READING_STATE: "Reading the HA state of both members",
  SYNCING: "Sending the sync command to the active member" };
const open = (state?: string) => state === "QUEUED" || state === "READING_STATE" || state === "SYNCING";

type Unit = Pick<CpFailoverUnit, "clusterId" | "unitId" | "vendor" | "masked" | "members" | "configSync">;
export function PanConfigSyncAction({ unit, members, onDone, pollMs = 3000 }: {
  unit: Unit; members?: ReadinessMember[]; onDone?: () => void; pollMs?: number;
}) {
  const facts = unit.configSync;
  const [dialog, setDialog] = useState(false);
  const [busy, setBusy] = useState(false);
  const [record, setRecord] = useState<PanConfigSyncRecord | null>(null);
  const [readiness, setReadiness] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const lock = useRef(false), alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);
  if (unit.vendor !== "palo_alto" || !facts || (!facts.canRun && !record)) return null;
  const roster = members ?? unit.members ?? [];
  const name = (id: string | null | undefined, fallback: string) => roster.find(m => m.device_id === id)?.hostname ?? fallback;
  const source = name(facts.sourceRef, "the active member"), target = name(facts.targetRef, "the passive member");
  const wait = () => new Promise(resolve => window.setTimeout(resolve, pollMs));
  const follow = async (first: PanConfigSyncRecord) => {
    let current = first;
    for (let i = 0; i < 100 && open(current.state) && alive.current; i++) {
      await wait();
      current = await getPanConfigSync(current.syncId);
      if (alive.current) setRecord(current);
    }
    if (current.readinessRunId) {
      setReadiness("Re-running the pre-checks");
      for (let i = 0; i < 60 && alive.current; i++) {
        const run = await getCpFailoverRun(current.readinessRunId, "palo_alto");
        if (run.state === "DONE" || run.state === "STOPPED") {
          const sync = run.checks.filter(c => c.checkNo === 4 && c.phase === "pre").map(c => c.status);
          const verdict = sync.length && sync.every(s => s === "PASS") ? "Configuration sync passes"
            : sync.includes("FAIL") ? "Configuration sync still fails" : "Configuration sync is unknown";
          if (alive.current) setReadiness(`Pre-checks finished: ${verdict}.`);
          break;
        }
        await wait();
      }
    } else if (current.readinessRefusal) {
      setReadiness(`The pre-checks were not re-run (${configSyncText(current.readinessRefusal)}). Run the pre-checks.`);
    }
    if (alive.current) onDone?.();
  };
  const submit = async () => {
    if (lock.current || !facts.sourceRef || !facts.targetRef) return;
    lock.current = true; setBusy(true); setError(null);
    try {
      const created = await requestPanConfigSync(unit, facts.sourceRef, facts.targetRef);
      setDialog(false); setRecord(created); setReadiness(null);
      await follow(created);
    } catch (e) {
      const body = (e as { body?: { code?: string; message?: string } })?.body;
      setError(body?.code === "BUSINESS_HOURS_CONFIRM_REQUIRED" ? configSyncText(body.code) : body?.code ? configSyncText(body.code) : "Request failed");
    } finally { lock.current = false; setBusy(false); }
  };
  const running = open(record?.state);
  return <Box data-config-sync sx={{ mt: 0.5 }} onClick={event => event.stopPropagation()}>
    {facts.canRun && !running && <M3Button deviceJob emphasis="tonal" disabled={busy || Boolean(facts.refusal)}
      onClick={() => { setError(null); setDialog(true); }}>Sync configuration to passive</M3Button>}
    {facts.canRun && facts.refusal && !record && <Typography variant="caption" component="div" sx={{ color: m3.onSurfaceVar }}>{configSyncText(facts.refusal)}</Typography>}
    {record && <Typography role="status" variant="body2">{running ? stageText[record.state] ?? record.state
      : record.state === "DONE" ? configSyncText("CONFIG_SYNC_REQUESTED") : configSyncText(record.outcome)}</Typography>}
    {readiness && <Typography role="status" variant="body2">{readiness}</Typography>}
    {error && !dialog && <Typography role="alert" variant="body2" color="error">{error}</Typography>}
    <Dialog open={dialog} onClose={() => { if (!busy) setDialog(false); }}>
      <DialogTitle>Sync the configuration to the passive member?</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <Typography>Source (active): {source}</Typography>
        <Typography>Target (passive): {target}</Typography>
        <Typography>neXus sends <Box component="code">{CONFIG_SYNC_COMMAND}</Box> once, to {source} only. The running configuration of {source} replaces the running configuration of {target}.</Typography>
        <Typography>First it reads the HA state of both members. It sends nothing unless {source} is still active, {target} is still passive and the configuration is still not synchronized.</Typography>
        <Typography>Nothing is retried. Afterwards the pre-checks run again for this unit.</Typography>
        {error && <Typography role="alert" color="error">{error}</Typography>}
      </Stack></DialogContent>
      <DialogActions><M3Button emphasis="text" disabled={busy} onClick={() => setDialog(false)}>Cancel</M3Button>
        <M3Button deviceJob emphasis="filled" disabled={busy} onClick={() => void submit()}>Sync configuration</M3Button></DialogActions>
    </Dialog>
  </Box>;
}
