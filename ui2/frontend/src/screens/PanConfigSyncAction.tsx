import { useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { M3Button } from "../shell/M3Widgets";
import { PRODUCT_NAME } from "../brand/product";
import { formatStamp } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import { getCpFailoverRun, getPanConfigSync, requestPanConfigSync,
  type CpFailoverSummary, type CpFailoverUnit, type PanConfigSyncRecord, type ReadinessMember } from "../auth/adminApi";

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
  INITIATOR_NOT_AUTHORIZED_AT_START: "Stopped before contact: the requesting administrator no longer holds the role or is disabled. Nothing was sent.",
  DIRECTORY_AUTHORITY_STALE_AT_START: "Stopped before contact: directory group membership was not refreshed in time. Nothing was sent.",
  DIRECTORY_UNREACHABLE_AT_START: "The directory could not be reached to refresh group membership. Nothing was sent; retry when the directory is reachable.",
};
export const configSyncText = (code?: string | null) => code ? words[code] ?? code : "";
export const CONFIG_SYNC_SETTLING = "Sync requested -- the passive member applies it; readiness re-runs in about a minute";
export const configSyncSettlingText = (requestedAt?: string | null) =>
  requestedAt ? `${CONFIG_SYNC_SETTLING} (${formatStamp(requestedAt)})` : CONFIG_SYNC_SETTLING;
const stageText: Record<string, string> = { QUEUED: "Queued", READING_STATE: "Reading the HA state of both members",
  SYNCING: "Sending the sync command to the active member" };
const open = (state?: string) => state === "QUEUED" || state === "READING_STATE" || state === "SYNCING";
const isAccepted = (r?: PanConfigSyncRecord | null) =>
  Boolean(r && (r.state === "DONE" || r.outcome === "CONFIG_SYNC_REQUESTED" || r.deviceStatus === "success"));

export function isConfigSyncInProgress(check?: { checkNo?: number; derived?: unknown; summary?: string | null } | null): boolean {
  if (!check || (check.checkNo !== undefined && check.checkNo !== 4)) return false;
  if (typeof check.summary === "string" && check.summary.toLowerCase().includes("synchronization in progress")) return true;
  const d = check.derived;
  if (!d) return false;
  const text = (typeof d === "string" ? d : JSON.stringify(d)).toLowerCase();
  return text.includes("synchronization in progress") || text.includes("config_sync_in_progress") || text.includes('"in_progress"');
}

type Unit = Pick<CpFailoverUnit, "clusterId" | "unitId" | "vendor" | "masked" | "members" | "configSync"> & {
  readiness?: CpFailoverSummary["readiness"];
};

export function panConfigSyncPhase(unit?: Unit | null, overrideRecord?: PanConfigSyncRecord | null): {
  phase: "NONE" | "SETTLING" | "STILL_FAILS" | "PASSES";
  requestedAt: string | null;
} {
  if (!unit || (unit.vendor && unit.vendor !== "palo_alto")) return { phase: "NONE", requestedAt: null };
  const facts = unit.configSync;
  const latest = overrideRecord ?? facts?.latest ?? null;
  const checks = unit.readiness?.checks?.filter(c => c.checkNo === 4) ?? [];
  const inProgress = facts?.readinessOutcome === "IN_PROGRESS" || checks.some(isConfigSyncInProgress);
  const observedAt = facts?.readinessObservedAt ?? unit.readiness?.observedAt ?? null;
  const requestedAt = latest?.requestedAt ?? observedAt;
  if (!latest) return { phase: inProgress || facts?.settling ? "SETTLING" : "NONE", requestedAt };
  if (open(latest.state)) return { phase: "SETTLING", requestedAt: latest.requestedAt };
  if (!isAccepted(latest)) {
    return { phase: latest.outcome === "OUTCOME_UNKNOWN" && inProgress ? "SETTLING" : "NONE", requestedAt: latest.requestedAt };
  }
  if (latest.readinessRefusal) return { phase: "NONE", requestedAt: latest.requestedAt };
  if (inProgress || facts?.settling === true) return { phase: "SETTLING", requestedAt: latest.requestedAt };
  const observedMs = observedAt ? Date.parse(observedAt) : NaN;
  const finishedMs = latest.finishedAt ? Date.parse(latest.finishedAt) : NaN;
  const requestedMs = latest.requestedAt ? Date.parse(latest.requestedAt) : NaN;
  const followUpFinished = facts?.settling === false
    || (Number.isFinite(observedMs) && (Number.isFinite(finishedMs) ? observedMs >= finishedMs : Number.isFinite(requestedMs) && observedMs > requestedMs));
  if (!followUpFinished) return { phase: "SETTLING", requestedAt: latest.requestedAt };
  const failed = facts?.readinessOutcome === "FAIL"
    || (checks.length > 0 ? checks.some(c => (c.status ?? c.result) === "FAIL")
      : facts?.offered === true || (unit.readiness?.status === "NOT_READY" && unit.readiness?.failedCheck === "Configuration sync"));
  if (failed) return { phase: "STILL_FAILS", requestedAt: latest.requestedAt };
  const passed = facts?.readinessOutcome === "PASS" || (checks.length > 0 && checks.every(c => (c.status ?? c.result) === "PASS"));
  return { phase: passed ? "PASSES" : "NONE", requestedAt: latest.requestedAt };
}

function adoptableRecord(unit: Unit): PanConfigSyncRecord | null {
  const latest = unit.configSync?.latest ?? null;
  if (!latest) return null;
  if (open(latest.state)) return latest;
  const { phase } = panConfigSyncPhase(unit, latest);
  return phase === "SETTLING" || phase === "STILL_FAILS" ? latest : null;
}

/** Refusals a fresh pre-check run clears: the action offers that run instead of a dead button (PO 2026-11-24 "tıklanamıyor"). */
const STALE_READINESS = new Set(["READINESS_NOT_FRESH", "READINESS_REQUIRED"]);

export function PanConfigSyncAction({ unit, members, onDone, pollMs = 3000, onRecheck, recheckBusy = false, recheckRunning = false }: {
  unit: Unit; members?: ReadinessMember[]; onDone?: () => void; pollMs?: number;
  /** Runs the unit's pre-checks (the header's Re-check); offered when the only refusal is stale or missing pre-checks. */
  onRecheck?: () => void; recheckBusy?: boolean; recheckRunning?: boolean;
}) {
  const facts = unit.configSync;
  const [dialog, setDialog] = useState(false);
  const [busy, setBusy] = useState(false);
  // Argon P2-1: a sync already open or settling when the page loads is shown and followed; a new unit starts clean.
  const [record, setRecord] = useState<PanConfigSyncRecord | null>(() => adoptableRecord(unit));
  const [readiness, setReadiness] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const lock = useRef(false), alive = useRef(true), following = useRef<string | null>(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const latestAdopted = adoptableRecord(unit);
  const latestToFollow = latestAdopted && (open(latestAdopted.state)
    || (latestAdopted.state === "DONE" && Boolean(latestAdopted.readinessRunId) && panConfigSyncPhase(unit, latestAdopted).phase === "SETTLING"
      && facts?.readinessOutcome !== "IN_PROGRESS" && !(unit.readiness?.checks ?? []).some(isConfigSyncInProgress))) ? latestAdopted : null;
  const shownUnit = useRef(unit.unitId);
  const propPhase = panConfigSyncPhase(unit, latestAdopted).phase;
  useEffect(() => {
    // Another unit: start clean. The same unit: adopt a sync opened or settling elsewhere (another tab, the CLI) and follow it;
    // a result already on screen stays when the refreshed facts no longer carry an open sync.
    if (shownUnit.current !== unit.unitId) {
      shownUnit.current = unit.unitId; setRecord(latestAdopted); setReadiness(null); setError(null);
    } else if (latestAdopted && !following.current) {
      setRecord(latestAdopted);
      setReadiness(null);
    }
    if (latestToFollow) void follow(latestToFollow);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [unit.unitId, latestAdopted?.syncId, latestAdopted?.state, latestAdopted?.deviceStatus, propPhase, facts?.readinessObservedAt, facts?.readinessOutcome, facts?.settling, unit.readiness?.observedAt]);
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
        if (misses >= 5 && alive.current) setReadiness("The sync status could not be read just now; it is shown again when the page is refreshed.");
        return;
      }
      if (current.readinessRunId) {
        if (!isAccepted(current) && alive.current) setReadiness("Re-running the pre-checks");
        for (let i = 0, failed = 0; i < 60 && alive.current && failed < 5; i++) {
          try {
            const run = await getCpFailoverRun(current.readinessRunId, "palo_alto");
            if (!run) break;
            failed = 0;
            if (run.state === "DONE" || run.state === "STOPPED") {
              const syncChecks = (run.checks ?? []).filter(c => c.checkNo === 4 && c.phase === "pre");
              if (syncChecks.some(isConfigSyncInProgress)) {
                if (alive.current) setReadiness(configSyncSettlingText(current.requestedAt));
              } else {
                const sync = syncChecks.map(c => c.status ?? c.result);
                const verdict = sync.length && sync.every(s => s === "PASS") ? "Configuration sync passes"
                  : sync.includes("FAIL") ? "Configuration sync still fails" : "Configuration sync is unknown";
                if (alive.current) setReadiness(`Pre-checks finished: ${verdict}.`);
              }
              break;
            } else if (isAccepted(current) && alive.current) {
              setReadiness(configSyncSettlingText(current.requestedAt));
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
  const syncPhase = panConfigSyncPhase(unit, record);
  if (unit.vendor !== "palo_alto" || !facts || (!facts.canRun && !record && syncPhase.phase === "NONE")) return null;
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
  const accepted = isAccepted(record);
  const stale = Boolean(onRecheck && facts.refusal && STALE_READINESS.has(facts.refusal));
  const readinessLine = readiness
    ?? (syncPhase.phase === "SETTLING" ? configSyncSettlingText(syncPhase.requestedAt)
      : syncPhase.phase === "STILL_FAILS" ? "Pre-checks finished: Configuration sync still fails." : null);
  const isSettling = readinessLine ? readinessLine.startsWith(CONFIG_SYNC_SETTLING) : false;
  return <Box data-config-sync sx={{ mt: 0.5 }} onClick={event => event.stopPropagation()}>
    {facts.canRun && !running && !isSettling && (stale
      ? <M3Button deviceJob emphasis="tonal" disabled={busy || recheckBusy} onClick={() => { setError(null); onRecheck?.(); }}>
          {recheckRunning ? "Running the pre-checks…" : "Re-check, then sync"}</M3Button>
      : <M3Button deviceJob emphasis="tonal" disabled={busy || Boolean(facts.refusal)}
          onClick={() => { setError(null); setDialog(true); }}>Sync configuration to passive</M3Button>)}
    {facts.canRun && facts.refusal && !record && !isSettling && <Typography variant="caption" component="div" sx={{ color: m3.onSurfaceVar }}>
      {stale ? "The pre-checks are older than 60 minutes. This runs them again; if configuration sync still fails, Sync configuration to passive is offered here."
        : configSyncText(facts.refusal)}</Typography>}
    {record && <Typography role="status" variant="body2">{running && record.deviceStatus !== "success" ? stageText[record.state] ?? record.state
      : accepted ? configSyncText("CONFIG_SYNC_REQUESTED") : configSyncText(record.outcome)}</Typography>}
    {readinessLine && <Typography role="status" variant="body2">{readinessLine}</Typography>}
    {error && !dialog && <Typography role="alert" variant="body2" color="error">{error}</Typography>}
    <Dialog open={dialog} onClose={() => { if (!busy) setDialog(false); }}>
      <DialogTitle>Sync the configuration to the passive member?</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <Typography>Source (active): {source}</Typography>
        <Typography>Target (passive): {target}</Typography>
        <Typography>{PRODUCT_NAME} sends <Box component="code">{CONFIG_SYNC_COMMAND}</Box> once, to {source} only. The running configuration of {source} replaces the running configuration of {target}.</Typography>
        <Typography>First it reads the HA state of both members. It sends nothing unless {source} is still active, {target} is still passive and the configuration is still not synchronized.</Typography>
        <Typography>Nothing is retried. Afterwards the pre-checks run again for this unit.</Typography>
        {error && <Typography role="alert" color="error">{error}</Typography>}
      </Stack></DialogContent>
      <DialogActions><M3Button emphasis="text" disabled={busy} onClick={() => setDialog(false)}>Cancel</M3Button>
        <M3Button deviceJob emphasis="filled" disabled={busy} onClick={() => void submit()}>Sync configuration</M3Button></DialogActions>
    </Dialog>
  </Box>;
}
