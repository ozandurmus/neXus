import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { JobLogsPanel } from "../src/screens/JobLogsPanel";
import { JobReconcileDialog } from "../src/screens/JobReconcileDialog";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { JOB_TYPE_LABELS, jobStateWord } from "../src/shell/presenters";
import { JOB_PILLS } from "../src/screens/JobLogsPanel";
import { EVIDENCE_MAX, FAILOVER_EXECUTION_JOB_TYPES, FAILOVER_RELEASE_NOTE, RECONCILE_STATUS_WORD, isFailoverRunJob, isReconcilable, reconcileRefusalWords } from "../src/jobs/jobReconcile";
import { planesAllGranted } from "../src/shell/actionAccess";

afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

const session = (permissions: readonly string[]): SessionInfo =>
  ({ displayName: "synthetic", roleTokens: [], permissions, onSignOut: () => undefined });
const withSession = (permissions: readonly string[], ui: ReactNode) =>
  <SessionContext.Provider value={session(permissions)}>{ui}</SessionContext.Provider>;

/** Planes the server gives the Backup Admin / an operator / an onboarding admin (SessionStatusController). */
const BACKUP_ADMIN = ["Operations", "Admin"];
const OPERATOR = ["Devices", "Config", "Compliance", "Operations"];
const ONBOARDING_ADMIN = ["Devices", "Config", "Admin"];

const UNKNOWN_JOB = { job_id: "job-unknown-1", state: "OUTCOME_UNKNOWN", job_type: "inventory", target_device_id: "opaque-001" };
const FAILOVER_JOB = { job_id: "job-failover-1", state: "OUTCOME_UNKNOWN", job_type: "cp_cluster_failover", target_device_id: "opaque-001" };

function stubJobs(items: unknown[], reconcile?: (init: RequestInit) => Response) {
  const fetch = vi.fn(async (input: string, init?: RequestInit) => {
    if (input === "/session/status") return new Response(JSON.stringify({ csrf_token: "synthetic-csrf" }));
    if (input.endsWith("/reconcile") && reconcile) return reconcile(init ?? {});
    if (input.startsWith("/api/v2/jobs/facets")) return new Response(JSON.stringify({ states: [], job_types: [] }));
    if (input.startsWith("/api/v2/jobs/stats")) return new Response(JSON.stringify({ by_state: {} }));
    if (input.startsWith("/api/v2/jobs")) return new Response(JSON.stringify({ items, total: items.length, page: 1, page_size: 50 }));
    return new Response(JSON.stringify({ devices: [] }));
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

const reconcileCalls = (fetch: ReturnType<typeof stubJobs>) =>
  fetch.mock.calls.filter(([url]) => String(url).endsWith("/reconcile"));
const json = (body: unknown, status: number) => new Response(JSON.stringify(body), { status });

describe("Reconcile gating on the job list", () => {
  it("shows the status and the Reconcile action to a role that holds both the Admin and Operations planes", async () => {
    stubJobs([UNKNOWN_JOB]);
    render(withSession(BACKUP_ADMIN, <JobLogsPanel />));
    expect(await screen.findByText(RECONCILE_STATUS_WORD)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: `Reconcile job ${UNKNOWN_JOB.job_id}` })).toBeInTheDocument();
  });

  it.each([["an operator", OPERATOR], ["an onboarding admin", ONBOARDING_ADMIN], ["a viewer", ["Devices", "Config", "Compliance"]]])(
    "shows the status but no Reconcile action to %s", async (_who, permissions) => {
      stubJobs([UNKNOWN_JOB]);
      render(withSession(permissions, <JobLogsPanel />));
      expect(await screen.findByText(RECONCILE_STATUS_WORD)).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /Reconcile/ })).toBeNull();
      expect(screen.queryByRole("columnheader", { name: "Actions" })).toBeNull();
    });

  it("treats a session that carries no plane list as unrestricted, like the rest of the shell", async () => {
    stubJobs([UNKNOWN_JOB]);
    render(<JobLogsPanel />);
    expect(await screen.findByRole("button", { name: `Reconcile job ${UNKNOWN_JOB.job_id}` })).toBeInTheDocument();
  });

  it("offers no action for a job that is not in OUTCOME_UNKNOWN", async () => {
    stubJobs([{ job_id: "job-done", state: "COMPLETED", job_type: "inventory", target_device_id: "opaque-001" },
      { job_id: "job-rec", state: "RECONCILED", job_type: "inventory", target_device_id: "opaque-001" }]);
    render(withSession(BACKUP_ADMIN, <JobLogsPanel />));
    await screen.findByText("Completed");
    expect(screen.queryByText(RECONCILE_STATUS_WORD)).toBeNull();
    expect(screen.queryByRole("button", { name: /Reconcile/ })).toBeNull();
  });

  it("shows no action for a failover run's job and says in its detail that it is released through the failover incident flow", async () => {
    stubJobs([FAILOVER_JOB]);
    render(withSession(BACKUP_ADMIN, <JobLogsPanel />));
    await screen.findByText("Outcome UNKNOWN");
    expect(screen.queryByText(RECONCILE_STATUS_WORD)).toBeNull();
    expect(screen.queryByRole("button", { name: /Reconcile/ })).toBeNull();
    fireEvent.click(screen.getByText("Outcome UNKNOWN").closest("tr")!);
    expect(await screen.findByText(FAILOVER_RELEASE_NOTE)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Reconcile/ })).toBeNull();
  });

  it("repeats the status and the action in the job detail", async () => {
    stubJobs([UNKNOWN_JOB]);
    render(withSession(BACKUP_ADMIN, <JobLogsPanel />));
    fireEvent.click((await screen.findByText(RECONCILE_STATUS_WORD)).closest("tr")!);
    const detail = await waitFor(() => {
      const node = document.querySelector(`[data-reconcile-detail="${UNKNOWN_JOB.job_id}"]`);
      expect(node).not.toBeNull();
      return node as HTMLElement;
    });
    expect(within(detail).getByRole("status")).toHaveTextContent(RECONCILE_STATUS_WORD);
    fireEvent.click(within(detail).getByRole("button", { name: /Reconcile/ }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });

  it("records the decision from the list, then refreshes the jobs", async () => {
    let recorded = false;
    const fetch = vi.fn(async (input: string, init?: RequestInit) => {
      if (input === "/session/status") return json({ csrf_token: "synthetic-csrf" }, 200);
      if (input.endsWith("/reconcile")) { recorded = true; return json({ jobId: UNKNOWN_JOB.job_id, state: "RECONCILED", reconciledOutcome: "RECONCILED_FAILURE" }, 200); }
      if (input.startsWith("/api/v2/jobs/facets")) return json({ states: [], job_types: [] }, 200);
      if (input.startsWith("/api/v2/jobs/stats")) return json({ by_state: {} }, 200);
      if (input.startsWith("/api/v2/jobs")) return json({ items: [recorded ? { ...UNKNOWN_JOB, state: "RECONCILED" } : UNKNOWN_JOB], total: 1 }, 200);
      void init;
      return json({ devices: [] }, 200);
    });
    vi.stubGlobal("fetch", fetch);
    render(withSession(BACKUP_ADMIN, <JobLogsPanel />));
    fireEvent.click(await screen.findByRole("button", { name: `Reconcile job ${UNKNOWN_JOB.job_id}` }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByLabelText(/It did not work/));
    fireEvent.change(within(dialog).getByLabelText(/Evidence/), { target: { value: "Rule absent on the device" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    await waitFor(() => expect(screen.queryByText(RECONCILE_STATUS_WORD)).toBeNull());
    expect(screen.queryByRole("button", { name: /Reconcile/ })).toBeNull();
  });
});

describe("JobReconcileDialog", () => {
  const open = (props: Partial<Parameters<typeof JobReconcileDialog>[0]> = {}) => {
    const onClose = vi.fn(); const onReconciled = vi.fn(); const onStale = vi.fn();
    render(<JobReconcileDialog jobId="job-1" onClose={onClose} onReconciled={onReconciled} onStale={onStale} {...props} />);
    return { onClose, onReconciled, onStale, dialog: screen.getByRole("dialog") };
  };
  const confirm = () => screen.getByRole("button", { name: "Confirm" });
  const evidenceBox = () => screen.getByLabelText(/Evidence/);

  it("offers the three legal outcomes in plain words and starts with none chosen", () => {
    stubJobs([]);
    open();
    const radios = screen.getAllByRole("radio");
    expect(radios.map((radio) => (radio as HTMLInputElement).value)).toEqual(["RECONCILED_SUCCESS", "RECONCILED_FAILURE", "RECONCILED_STILL_UNKNOWN"]);
    expect(radios.every((radio) => !(radio as HTMLInputElement).checked)).toBe(true);
    for (const label of ["It worked", "It did not work", "Still not known"]) expect(screen.getByText(label)).toBeInTheDocument();
  });

  it("keeps Confirm disabled until an outcome is chosen and evidence is written", () => {
    stubJobs([]);
    open();
    expect(confirm()).toBeDisabled();
    fireEvent.click(screen.getByLabelText(/It worked/));
    expect(confirm()).toBeDisabled();
    fireEvent.change(evidenceBox(), { target: { value: "   " } });
    expect(confirm()).toBeDisabled();
    fireEvent.change(evidenceBox(), { target: { value: "Checked the device" } });
    expect(confirm()).toBeEnabled();
  });

  it("counts the evidence and caps it at 2000 characters", () => {
    stubJobs([]);
    const { dialog } = open();
    expect(dialog.querySelector("[data-evidence-counter]")).toHaveTextContent(`0 / ${EVIDENCE_MAX}`);
    fireEvent.change(evidenceBox(), { target: { value: "abcde" } });
    expect(dialog.querySelector("[data-evidence-counter]")).toHaveTextContent(`5 / ${EVIDENCE_MAX}`);
    expect(evidenceBox()).toHaveAttribute("maxlength", String(EVIDENCE_MAX));
    expect(EVIDENCE_MAX).toBe(2000);
  });

  it("sends only the outcome and the trimmed evidence to the job's reconcile endpoint, then reports success and closes", async () => {
    const fetch = stubJobs([], () => json({ jobId: "job-1", state: "RECONCILED", reconciledOutcome: "RECONCILED_SUCCESS" }, 200));
    const { onClose, onReconciled } = open();
    fireEvent.click(screen.getByLabelText(/It worked/));
    fireEvent.change(evidenceBox(), { target: { value: "  Saw the change in the device log  " } });
    fireEvent.click(confirm());
    await waitFor(() => expect(onReconciled).toHaveBeenCalledTimes(1));
    expect(onClose).toHaveBeenCalledTimes(1);
    const [[url, init]] = reconcileCalls(fetch);
    expect(url).toBe("/api/v2/jobs/job-1/reconcile");
    expect((init as RequestInit).method).toBe("POST");
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({ outcome: "RECONCILED_SUCCESS", evidence: "Saw the change in the device log" });
  });

  it("does not call the server when cancelled", () => {
    const fetch = stubJobs([]);
    const { onClose, onReconciled } = open();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onReconciled).not.toHaveBeenCalled();
    expect(reconcileCalls(fetch)).toHaveLength(0);
  });

  it.each([
    [404, "JOB_NOT_FOUND", "This job no longer exists."],
    [409, "JOB_NOT_OUTCOME_UNKNOWN", "This job is no longer waiting for reconciliation. It may already have been reconciled."],
    [409, "JOB_BELONGS_TO_FAILOVER_RUN", FAILOVER_RELEASE_NOTE],
    [400, "JOB_RECONCILIATION_INVALID", "The outcome or the evidence was not accepted. Choose one outcome and write evidence of 1 to 2000 characters."],
  ])("shows the server's reason in plain words for %i %s and stays open", async (status, code, words) => {
    stubJobs([], () => json({ error: code }, status));
    const { onClose, onReconciled, onStale } = open();
    fireEvent.click(screen.getByLabelText(/Still not known/));
    fireEvent.change(evidenceBox(), { target: { value: "evidence" } });
    fireEvent.click(confirm());
    expect(await screen.findByRole("alert")).toHaveTextContent(words);
    expect(screen.queryByText(code)).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    expect(onReconciled).not.toHaveBeenCalled();
    // A job that is gone or no longer unknown asks the list to refresh; an invalid request does not.
    expect(onStale).toHaveBeenCalledTimes(status === 400 ? 0 : 1);
    expect(confirm()).toBeEnabled();
  });
});

describe("reconcile helpers", () => {
  it("recognises a failover run's job types and leaves readiness and HA sync reconcilable", () => {
    for (const type of ["cp_cluster_failover", "pan_cluster_failover", "CP_CLUSTER_FAILOVER"]) {
      expect(isFailoverRunJob(type)).toBe(true);
    }
    for (const type of ["inventory", "cp_failover_readiness", "pan_failover_readiness", "asa_cluster_failover", "pan_failover_config_sync", "cp_gateway_backup", "", null, undefined]) {
      expect(isFailoverRunJob(type)).toBe(false);
    }
    expect(isReconcilable({ state: "OUTCOME_UNKNOWN", job_type: "asa_cluster_failover" })).toBe(true);
    expect(isReconcilable({ state: "OUTCOME_UNKNOWN", job_type: "inventory" })).toBe(true);
    expect(isReconcilable({ state: "OUTCOME_UNKNOWN", job_type: "cp_cluster_failover" })).toBe(false);
    expect(isReconcilable({ state: "RECONCILED", job_type: "inventory" })).toBe(false);
  });

  it("classifies every job type the presenters know, so a new type must be decided", () => {
    // Labels that are audit action names, not job types: no job row carries them, so no Reconcile decision applies.
    const notJobTypes = new Set(["cp_failover_start", "pan_failover_start"]);
    const reconcilable = new Set([
      "cp_failover_readiness", "pan_failover_readiness", "asa_cluster_failover", "pan_failover_config_sync",
    ]);
    for (const type of Object.keys(JOB_TYPE_LABELS)) {
      if (notJobTypes.has(type)) continue;
      const classified = FAILOVER_EXECUTION_JOB_TYPES.has(type) || reconcilable.has(type) || !/failover/.test(type);
      expect(classified, `${type} must be classified as failover execution or reconcilable`).toBe(true);
      expect(isFailoverRunJob(type)).toBe(FAILOVER_EXECUTION_JOB_TYPES.has(type));
    }
    for (const type of FAILOVER_EXECUTION_JOB_TYPES) expect(isFailoverRunJob(type)).toBe(true);
  });

  it("keeps RECONCILED jobs in the Other outcome pill and words the state", () => {
    expect(JOB_PILLS.find((p) => p.id === "other")!.states.split(",")).toEqual(
      ["OUTCOME_UNKNOWN", "RECONCILED", "REJECTED", "CANCELLED"]);
    expect(jobStateWord("RECONCILED")).toBe("Reconciled");
    expect(jobStateWord("OUTCOME_UNKNOWN")).toBe("Outcome UNKNOWN");
  });

  it("words a refusal, and keeps the code and status of one it does not know", () => {
    expect(reconcileRefusalWords({ status: 403, body: {} })).toBe("Your role does not allow this action.");
    expect(reconcileRefusalWords({ status: 500, body: { error: "SOMETHING_NEW" } })).toBe("SOMETHING_NEW (HTTP 500)");
  });

  it("requires every named plane, and treats an absent plane list as unrestricted", () => {
    expect(planesAllGranted(["Operations", "Admin"], ["Admin", "Operations"])).toBe(true);
    expect(planesAllGranted(["Operations"], ["Admin", "Operations"])).toBe(false);
    expect(planesAllGranted(undefined, ["Admin", "Operations"])).toBe(true);
    expect(planesAllGranted([], ["Admin", "Operations"])).toBe(true);
  });
});
