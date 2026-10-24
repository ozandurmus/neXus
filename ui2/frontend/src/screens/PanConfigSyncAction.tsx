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
  UNRESOLVED_DISPATCH: "A failover write on this unit has an unresolved outcome.",
  NOT_SENT_BEFORE_CONTACT: "Stopped before the device was contacted. Nothing was sent.",
  JOB_ENDED_WITHOUT_RESULT: "The job ended before the write. Nothing was sent.",
  FOLLOW_UP_UNAVAILABLE: "the re-run could not be started",
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
  // Argon P2-1: a sync already open when the page loads is shown and followed; a new unit starts clean.
  const [record, setRecord] = useState<PanConfigSyncRecord | null>(open(facts?.latest?.state) ? facts!.latest! : null);
  const [readiness, setReadiness] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const lock = useRef(false), alive = useRef(true), following = useRef<string | null>(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const latestOpen = open(facts?.latest?.state) ? facts!.latest! : null;
  const shownUnit = useRef(unit.unitId);
  useEffect(() => {
    // Another unit: start clean. The same unit: adopt a sync opened elsewhere (another tab, the CLI) and follow it;
    // a result already on screen stays when the refreshed facts no longer carry an open sync.
    if (shownUnit.current !== unit.unitId) { shownUnit.current = unit.unitId; setRecord(latestOpen); setReadiness(null); setError(null); }
    else if (latestOpen) setRecord(current => current?.syncId === latestOpen.syncId ? current : latestOpen);
    if (latestOpen) void follow(latestOpen);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [unit.unitId, latestOpen?.syncId]);
  const roster = members ?? unit.members ?? [];
  const name = (id: string | null | undefined, fallback: string) => roster.find(m => m.device_id === id)?.hostname ?? fallback;
  const source = name(facts?.sourceRef, "the active member"), target = name(facts?.targetRef, "the passive member");
  const wait = () => new Promise(resolve => window.setTimeout(resolve, pollMs));
  /** Polls until the sync and its readiness re-run end; a failed read is retried, never reported as a failed request. */
  async function follow(first: PanConfigSyncRecord) {
    if (following.current === first.syncId) return;
    following.current = first.syncId;
    let current = first, misses = 0;
    try {
      for (let i = 0; i < 100 && open(current.state) && alive.current && misses < 5; i++) {
        await wait();
        try { current = await getPanConfigSync(current.syncId); misses = 0; if (alive.current) setRecord(current); }
        catch { misses++; }
      }
      if (open(current.state)) {
        if (alive.current) setReadiness("The sync status could not be read just now; it is shown again when the page is refreshed.");
        return;
      }
      if (current.readinessRunId) {
        if (alive.current) setReadiness("Re-running the pre-checks");
        for (let i = 0, failed = 0; i < 60 && alive.current && failed < 5; i++) {
          try {
            const run = await getCpFailoverRun(current.readinessRunId, "palo_alto");
            failed = 0;
            if (run.state === "DONE" || run.state === "STOPPED") {
              const sync = run.checks.filter(c => c.checkNo === 4 && c.phase === "pre").map(c => c.status);
              const verdict = sync.length && sync.every(s => s === "PASS") ? "Configuration sync passes"
                : sync.includes("FAIL") ? "Configuration sync still fails" : "Configuration sync is unknown";
              if (alive.current) setReadiness(`Pre-checks finished: ${verdict}.`);
              break;
            }
          } catch { failed++; }
          await wait();
        }
      } else if (current.readinessRefusal && alive.current) {
        setReadiness(`The pre-checks were not re-run (${configSyncText(current.readinessRefusal)}). Run the pre-checks.`);
      }
    } finally {
      following.current = null;
      if (alive.current) onDone?.();
    }
  }
  if (unit.vendor !== "palo_alto" || !facts || (!facts.canRun && !record)) return null;
  const submit = async () => {
    if (lock.current || !facts.sourceRef || !facts.targetRef) return;
    lock.current = true; setBusy(true); setError(null);
    let created: PanConfigSyncRecord | null = null;
    try {
      created = await requestPanConfigSync(unit, facts.sourceRef, facts.targetRef);
      setDialog(false); setRecord(created); setReadiness(null);
    } catch (e) {
      const body = (e as { body?: { code?: string; message?: string } })?.body;
      setError(body?.code ? configSyncText(body.code) : "Request failed");
    } finally { lock.current = false; setBusy(false); }
    // The request was accepted: from here on only its progress is followed (Argon P2-1).
    if (created) await follow(created);
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
