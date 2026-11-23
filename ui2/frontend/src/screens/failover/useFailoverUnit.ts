import { useCallback, useEffect, useRef, useState } from "react";
import { approveCpFailover, getCpFailoverRun, listCpFailoverApprovals, listCpFailoverRuns, secondApproveCpFailover, startCpFailover, runCpFailoverReadiness,
  type CpFailoverApproval, type CpFailoverRun, type CpFailoverRunDetail, type CpFailoverUnit } from "../../auth/adminApi";
import { failoverExecutionDisabled } from "./failoverRowModel";
import { DEFAULT_VALIDITY_MINUTES, activeStates, errorText, failoverReadinessVerdict, isDirectoryUnreachable, type Draft } from "./failoverShared";
import { resumePolls } from "./ResumeReturn";

/**
 * The state and effects of one failover unit: its requests, runs and readiness run (read, then polled while something is
 * active), the remembered request draft, the "armed" flow that opens the confirm dialog once the unit is read, and the
 * confirm itself. `shown`: this unit is the one shown in the detail (it is read and polled only while it is). The policy
 * (`singleAdmin`) words the dialog; the server's answer to the request decides what happens.
 */
export function useFailoverUnit({ unit, shown, singleAdmin, onRefreshUnits }: { unit: CpFailoverUnit; shown: boolean;
  singleAdmin: boolean | null | undefined; onRefreshUnits: () => void }) {
  const storageKey = `failover-request:${unit.vendor ?? "check_point"}:${unit.unitId}`;
  const [draft, setDraft] = useState<Draft | null>(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey) ?? "null") as Draft | null; } catch { return null; }
  });
  const [approvals, setApprovals] = useState<CpFailoverApproval[]>([]);
  const [runs, setRuns] = useState<CpFailoverRun[]>([]);
  const [run, setRun] = useState<CpFailoverRunDetail | null>(null);
  const [readinessRun, setReadinessRun] = useState<CpFailoverRunDetail | null>(null);
  const [dialog, setDialog] = useState(false), [minutes, setMinutes] = useState<number>(DEFAULT_VALIDITY_MINUTES);
  const [note, setNote] = useState(""), [error, setError] = useState<string | null>(null);
  // Set by a failover button: the dialog opens once this unit's requests and runs are read (and the policy hint answered).
  const [armed, setArmed] = useState(false);
  const [canRetry, setCanRetry] = useState(false);
  const [busy, setBusy] = useState(false), [loaded, setLoaded] = useState(false);
  const lock = useRef(false), attempted = useRef<string | null>(null);
  const lastAction = useRef<(() => Promise<unknown>) | null>(null);
  const approvedDraft = useRef<CpFailoverApproval | null>(null);
  const lastReadiness = useRef<string | null>(null);
  const seenAt = useRef(unit.readinessObservedAt);
  seenAt.current = unit.readinessObservedAt;
  const remember = (value: Draft) => { sessionStorage.setItem(storageKey, JSON.stringify(value)); setDraft(value); };
  const executionDisabled = failoverExecutionDisabled(unit.vendor);
  const refresh = useCallback(async () => {
    try {
      const [windows, list] = await Promise.all([
        executionDisabled ? Promise.resolve([] as CpFailoverApproval[]) : listCpFailoverApprovals(unit),
        listCpFailoverRuns(unit),
      ]);
      const failover = list.find(item => item.kind !== "READINESS" && activeStates.includes(item.state)) ?? list.find(item => item.kind !== "READINESS");
      const readiness = list.find(item => item.kind === "READINESS");
      const [detail, checks] = await Promise.all([failover ? getCpFailoverRun(failover.runId, unit.vendor) : null,
        readiness ? getCpFailoverRun(readiness.runId, unit.vendor) : null]);
      setApprovals(windows); setRuns(list); setRun(detail); setReadinessRun(checks); setLoaded(true);
      if (checks && !activeStates.includes(checks.state) && lastReadiness.current !== checks.runId) {
        // The unit list was loaded with the latest finished readiness: reload it only when this run is newer than
        // what the list shows (a run that finished while we watched), not for the run already on screen at mount.
        const firstSight = lastReadiness.current === null;
        lastReadiness.current = checks.runId;
        const seen = checks.checks.map(c => c.observedAt).sort().at(-1);
        if (!firstSight || (seen && (!seenAt.current || Date.parse(seen) > Date.parse(seenAt.current)))) onRefreshUnits();
      }
    } catch (e) { setLoaded(false); setArmed(false); setError(errorText(e)); }
  }, [unit.unitId, unit.clusterId, unit.vendor, onRefreshUnits]);
  useEffect(() => { if (shown) void refresh(); }, [shown, refresh]);
  const pending = approvals.find(a => !a.revokedAt && Date.parse(a.windowUntil) > Date.now() && a.policy === "OPERATION_ADMIN_TWO_PERSON" && !a.approved);
  const bound = approvals.find(a => a.requestId === draft?.requestId && !a.revokedAt && Date.parse(a.windowUntil) > Date.now());
  const active = Boolean(run && activeStates.includes(run.state));
  const resuming = resumePolls(run?.resume?.state);
  const readinessActive = Boolean(readinessRun && activeStates.includes(readinessRun.state));
  useEffect(() => {
    if (!shown || (!active && !pending && !readinessActive && !resuming)) return;
    const timer = window.setInterval(() => { void refresh(); }, 3000);
    return () => window.clearInterval(timer);
  }, [shown, active, pending, readinessActive, resuming, refresh]);
  const act = async (action: () => Promise<unknown>) => {
    if (lock.current) return;
    lastAction.current = action;
    lock.current = true; setBusy(true); setError(null); setCanRetry(false);
    try { await action(); setDialog(false); await refresh(); }
    catch (e) { setError(errorText(e)); setCanRetry(isDirectoryUnreachable(e)); }
    finally { lock.current = false; setBusy(false); }
  };
  const start = async (approval: CpFailoverApproval) => {
    if (!approval.requestId || !approval.revision || !approval.executionNonce) throw new Error("Missing request binding");
    const result = await startCpFailover(unit, { requestId: approval.requestId, revision: approval.revision, executionNonce: approval.executionNonce, warningConfirmed: true });
    setRun(await getCpFailoverRun(result.runId, unit.vendor));
  };
  // The initiator's one warning confirmation survives refresh; second approval adds no new confirmation.
  useEffect(() => {
    if (shown && !unit.masked && unit.canStart && loaded && bound?.approved && bound.canStart && draft?.confirmedRevision === bound.revision
      && (!run || run.approvalId !== bound.approvalId) && !active && attempted.current !== bound.requestId) {
      attempted.current = bound.requestId!; void act(() => start(bound));
    }
  }, [shown, bound, draft, loaded, active, run, unit.masked, unit.canStart]);
  const idle = loaded && !busy && !active && !readinessActive;
  const canOperate = unit.masked !== true && unit.canStart && !executionDisabled;
  const canRunChecks = unit.masked !== true && (unit.canRunReadiness ?? unit.canStart);
  // A VSX chassis is failed over as its mode allows (contract §17): one chassis run in VSX HA mode, every virtual system in
  // turn on VSLS; with the mode unknown the server offers nothing (failoverRefusal).
  const failoverOffered = canOperate && !unit.failoverRefusal;
  const single = singleAdmin === true;
  const verdict = failoverReadinessVerdict(unit, readinessRun);
  const readinessAt = readinessRun?.checks.filter(c => c.phase === "pre").map(c => c.observedAt).sort().at(-1) ?? unit.readinessObservedAt;
  const refused = Boolean(unit.refusalReason) || Boolean(unit.chassisStartRefusal) || Boolean(unit.failoverRefusal);
  const noteText = draft?.reason ?? note;
  const openDialog = () => {
    if (draft?.confirmedRevision && bound?.approved && (!run || run.approvalId !== bound.approvalId)) { void act(() => start(bound)); return; }
    if (draft && (run?.approvalId === draft.requestId || Date.parse(draft.until) <= Date.now()
      || approvals.some(a => a.requestId === draft.requestId && a.revokedAt))) {
      sessionStorage.removeItem(storageKey); setDraft(null);
    }
    setNote(""); setMinutes(DEFAULT_VALIDITY_MINUTES); setError(null); setCanRetry(false); setDialog(true);
  };
  // One click: this unit is read first (unless it already is), then the dialog follows without a second click.
  const requestFailover = () => { setArmed(true); };
  useEffect(() => {
    if (!armed || !shown || !loaded || singleAdmin === undefined) return;
    setArmed(false);
    // A run or a request found while reading is shown in the detail instead of a second request.
    if (!active && !readinessActive && !pending) openDialog();
  }, [armed, shown, loaded, singleAdmin, active, readinessActive, pending]);
  const confirm = () => act(async () => {
    const now = Date.now();
    const request = draft ?? { requestId: crypto.randomUUID(), from: new Date(now).toISOString(),
      until: new Date(now + (single ? DEFAULT_VALIDITY_MINUTES : minutes) * 60_000).toISOString(), reason: note.trim() };
    remember(request);
    const currentBound = bound ?? (approvedDraft.current?.requestId === request.requestId ? approvedDraft.current : undefined);
    const approval = currentBound ?? await approveCpFailover(unit, request.from, request.until, request.reason, request.requestId);
    if (!approval.requestId || !approval.revision) throw new Error("Missing request revision");
    approvedDraft.current = approval;
    // The dialog said a second administrator must agree and the server needed none: nothing starts on words the reader
    // was not given. The request stands on the server, but the reader's confirmation is NOT recorded: a later click on the
    // row must show the dialog again instead of starting on the strength of this one. Confirming again in this dialog starts it.
    const unconfirmed = approval.approved && singleAdmin === false && !currentBound;
    remember(unconfirmed ? request : { ...request, confirmedRevision: approval.revision });
    if (unconfirmed) throw new Error("No second administrator was needed for this request, so it is already approved on the server. The failover has not been started. Confirm again to start it now.");
    if (approval.approved) {
      attempted.current = approval.requestId;
      await start(approval);
    }
  });
  const secondApprove = () => pending && void act(() => secondApproveCpFailover(unit, pending.requestId!, pending.revision!));
  const runChecks = () => void act(() => runCpFailoverReadiness(unit));
  const retry = () => { if (lastAction.current) void act(lastAction.current); };
  return { approvals, runs, run, readinessRun, dialog, setDialog, minutes, setMinutes, note, setNote, error, canRetry, armed, busy, loaded, draft,
    pending, bound, active, readinessActive, resuming, idle, canOperate, canRunChecks, failoverOffered, single, verdict, readinessAt, refused,
    noteText, executionDisabled, requestFailover, confirm, secondApprove, runChecks, retry, refresh };
}
export type FailoverUnitState = ReturnType<typeof useFailoverUnit>;
