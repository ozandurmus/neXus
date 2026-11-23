import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { CpFailoverPanel, FailoverRun, errorText, failoverReadinessVerdict, runStep } from "../src/screens/CpFailoverPanel";
import type { CpFailoverRunDetail } from "../src/auth/adminApi";
const api = vi.hoisted(() => ({ units: vi.fn(), approvals: vi.fn(), runs: vi.fn(), detail: vi.fn(), readiness: vi.fn(), approve: vi.fn(), second: vi.fn(), start: vi.fn(),
  incidents: vi.fn(), release: vi.fn(), resume: vi.fn(), approveResume: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({ listCpFailoverUnits: api.units, listCpFailoverApprovals: api.approvals,
  listCpFailoverRuns: api.runs, getCpFailoverRun: api.detail, runCpFailoverReadiness: api.readiness,
  approveCpFailover: api.approve, secondApproveCpFailover: api.second, startCpFailover: api.start,
  listFailoverIncidents: api.incidents, releaseFailoverIncident: api.release,
  resumeCpFailoverReturn: api.resume, approveCpFailoverResume: api.approveResume }));
const unit = { clusterId: "opaque-cluster", unitId: "opaque-cluster", cluster_member_ref: "CLS-ROMEO-01", vendor: "check_point" as const,
  mode: "HA", readinessObservedAt: "2026-10-06T09:00:00Z", canApprove: true, canStart: true, canSchedule: false, masked: false,
  members: [{ device_id: "opaque-1", hostname: "FW-ROMEO-01", ha_role: "ACTIVE" }, { device_id: "opaque-2", hostname: "FW-ROMEO-02", ha_role: "STANDBY" }] };
const approval = { approvalId: "00000000-0000-4000-8000-000000000001", requestId: "00000000-0000-4000-8000-000000000001", revision: 1, policy: "ADMIN_SINGLE", approved: true,
  executionNonce: "nonce-opaque", canStart: true, windowFrom: new Date(Date.now()-60_000).toISOString(), windowUntil: new Date(Date.now()+3_600_000).toISOString(), revokedAt: null };
const run = (overrides = {}): CpFailoverRunDetail => ({ runId: "run-opaque", approvalId: "00000000-0000-4000-8000-000000000001", scheduledFor: "", state: "PRECHECK", step: "PRECHECK",
  outcome: null, failedCheck: null, message: null, steps: [], checks: [], mutationPossible: false, ...overrides });
function setup() { return render(<ThemeProvider theme={m3Theme}><CpFailoverPanel memberDeviceId="opaque-1" /></ThemeProvider>); }
/** The row's action is there without expanding the unit (PO 2026-11-23). */
async function open() { setup(); await waitFor(() => expect(screen.getByRole("button", { name: "Start failover" })).toBeEnabled()); }
/** One click on the row opens the dialog; the work log note is the only thing typed. */
async function warning(note = "change CHG-123 planned failover") {
  fireEvent.click(screen.getByRole("button", { name: "Start failover" }));
  fireEvent.change(await screen.findByLabelText(/Work log note/), { target: { value: note } });
}
/** The session that approves and starts alone (ADMIN_SINGLE): the server offers it the incident release. */
const asAdmin = () => api.incidents.mockResolvedValue({ canRelease: true, roleNote: null, incidents: [] });
const HOUR = 3_600_000;
beforeEach(() => {
  vi.resetAllMocks(); vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000001"); sessionStorage.clear(); api.units.mockResolvedValue([unit]); api.approvals.mockResolvedValue([]); api.runs.mockResolvedValue([]);
  api.approve.mockResolvedValue(approval); api.start.mockResolvedValue({ runId: "run-opaque" }); api.detail.mockResolvedValue(run());
  api.incidents.mockResolvedValue({ canRelease: false, roleNote: null, incidents: [] });
});
it.each(["check_point", "palo_alto"] as const)("empty %s lookup has no actions", async vendor => {
  api.units.mockResolvedValue([]); render(<CpFailoverPanel memberDeviceId="opaque-1" vendor={vendor} />);
  expect(await screen.findByText("No eligible failover units")).toBeInTheDocument(); expect(screen.queryByRole("button")).toBeNull();
});
it.each([
  ["PLANNED", "PLANNED", 0, "Queued"], ["PRECHECK", "PRECHECK", 0, "Checking readiness"],
  ["FAILING_OVER", "FAILING_OVER", 1, "Failing over"], ["SWITCHED", "SWITCHED", 2, "Role change confirmed"],
  ["POSTCHECK", "POSTCHECK", 3, "Checking traffic and cluster after switch"], ["RETURNING", "RETURNING", 4, "Returning former active to standby"],
  ["POSTCHECK", "FINAL_POSTCHECK", 5, "Verifying final cluster health"], ["POSTCHECK", "POST_RETURN", 5, "Verifying final cluster health"],
  ["DONE", "DONE", 6, "Failover verified"],
] as const)("renders server state %s / %s", (state, step, index, label) => {
  expect(runStep(state, step)).toBe(index); render(<FailoverRun unit={unit} run={run({ state, step })} />);
  expect(screen.getByRole("status")).toHaveTextContent(label); expect(screen.getAllByText("Session continuity: not evaluated")).toHaveLength(2);
});
it.each([false, true, undefined])("reports stopped mutation certainty %s without guessing", mutationPossible => {
  render(<FailoverRun unit={unit} run={run({ state: "STOPPED", step: "POSTCHECK", mutationPossible, incidentRef: "incident-opaque", lastConfirmedRoles: { "opaque-1": "DOWN" },
    checks: [{ checkNo: 8, phase: "post", status: "UNKNOWN", result: "UNKNOWN", title: "Traffic rate", member: "Member 1", summary: "Not observed", blocking: true, derived: "192.0.2.1 SYNTHETIC-PRIVATE" }] })} />);
  expect(screen.getByRole("status")).toHaveTextContent(mutationPossible === false ? "Stopped before failover" : mutationPossible ? "Stopped after mutation — intervention required" : "mutation status unknown");
  expect(screen.getByText(/Last confirmed roles:/)).toHaveTextContent("FW-ROMEO-01: DOWN"); expect(screen.getByText(/Failed\/unknown check:/)).toHaveTextContent("Traffic rate · UNKNOWN");
  expect(screen.getByText(/Incident reference:/)).toHaveTextContent("incident-opaque"); expect(screen.queryByText(/SYNTHETIC-PRIVATE/)).toBeNull();
  expect(screen.queryByRole("button", { name: /Cancel/ })).toBeNull();
});
it.each(["UNSUPPORTED_MODE", "IDENTITY_NOT_RECORDED", "OPEN_INCIDENT", "INSUFFICIENT_EVIDENCE"])("lists refusal %s and prevents starting", async refusalReason => {
  api.units.mockResolvedValue([{ ...unit, refusalReason }]); setup(); fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
  expect(screen.getByText(refusalReason)).toBeInTheDocument(); expect(screen.getByRole("button", { name: "Start failover" })).toBeDisabled(); expect(api.start).not.toHaveBeenCalled();
});
it("aiview remains read-only even with inconsistent server permission flags", async () => {
  api.units.mockResolvedValue([{ ...unit, masked: true }]); setup(); fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
  await waitFor(() => expect(api.runs).toHaveBeenCalled());
  expect(screen.queryByRole("button", { name: /Start|Approve|Run pre-checks/ })).toBeNull();
});
// ---- PO 2026-11-23: an administrator fails over with one action and a work log note ----
it("an administrator starts from the row with one dialog: no date field, a required note, valid for 60 minutes", async () => {
  asAdmin(); await open();
  api.start.mockImplementation(async () => { api.runs.mockResolvedValue([{ runId: "run-opaque", kind: "FAILOVER", state: "PRECHECK" }]); return { runId: "run-opaque" }; });
  // The unit was never expanded: nothing of it is read until the action is used.
  expect(api.runs).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Start failover" }));
  const dialog = await screen.findByRole("dialog");
  expect(dialog.querySelector("[data-failover-policy]")).toHaveAttribute("data-failover-policy", "single");
  expect(within(dialog).getByText("Target: CLS-ROMEO-01")).toBeInTheDocument();
  expect(within(dialog).getByText(/Traffic may be interrupted/)).toBeInTheDocument();
  expect(within(dialog).getByText("Valid for 60 minutes.")).toBeInTheDocument();
  expect(within(dialog).queryByLabelText(/Window ends/)).toBeNull();
  expect(dialog.querySelector("input[type='datetime-local']")).toBeNull();
  expect(within(dialog).queryByRole("radio")).toBeNull();
  expect(dialog).not.toHaveTextContent(/approv|revision/i);
  const ok = within(dialog).getByRole("button", { name: "Fail over now" });
  expect(ok).toBeDisabled();
  fireEvent.change(within(dialog).getByLabelText(/Work log note/), { target: { value: "   " } });
  expect(ok).toBeDisabled();
  fireEvent.change(within(dialog).getByLabelText(/Work log note/), { target: { value: "  change CHG-123 planned failover  " } });
  const before = Date.now(); fireEvent.click(ok); fireEvent.click(ok);
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1)); expect(api.approve).toHaveBeenCalledTimes(1);
  const [target, from, until, reason, requestId] = api.approve.mock.calls[0];
  expect(target).toEqual(expect.objectContaining({ unitId: unit.unitId }));
  expect(Date.parse(until) - Date.parse(from)).toBe(HOUR);
  expect(Date.parse(from)).toBeGreaterThanOrEqual(before); expect(Date.parse(from)).toBeLessThanOrEqual(Date.now());
  expect(reason).toBe("change CHG-123 planned failover"); expect(requestId).toBe(approval.requestId);
  expect(api.start).toHaveBeenCalledWith(expect.objectContaining({ unitId: unit.unitId }), { requestId: approval.requestId, revision: 1, executionNonce: approval.executionNonce, warningConfirmed: true });
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull()); expect(screen.queryByText("Awaiting second approval")).toBeNull();
  // The unit opened with the action, so the run is on screen without another click.
  expect(await screen.findByText(/Run: run-opaque/)).toBeInTheDocument();
});
it("Enter in the note field confirms an administrator's failover", async () => {
  asAdmin(); await open(); await warning("change CHG-124");
  fireEvent.keyDown(screen.getByLabelText(/Work log note/), { key: "Enter" });
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1));
  expect(api.approve.mock.calls[0][3]).toBe("change CHG-124");
});
it("the row action on a unit with a run in progress opens the unit and asks nothing", async () => {
  asAdmin(); api.runs.mockResolvedValue([run()]); await open();
  fireEvent.click(screen.getByRole("button", { name: "Start failover" }));
  expect(await screen.findByText(/Run: run-opaque/)).toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole("button", { name: "Start failover" })).toBeDisabled());
  expect(screen.queryByRole("dialog")).toBeNull(); expect(api.approve).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled();
});
it.each([
  ["a READY run", { outcome: "READY" }, "ready", "Readiness: Ready"],
  ["a READY run with a warning", { outcome: "READY", warn: true }, "warning", "Readiness: Ready with 1 warning · Traffic rate"],
  ["a run with no verdict", { outcome: "UNKNOWN" }, "unknown", "Readiness: Unknown: the last readiness run recorded no verdict"],
  ["a run still in progress", { outcome: "READY", state: "PRECHECK" }, "unknown", "Readiness: Unknown: pre-checks are still running"],
  ["no readiness run", null, "unknown", "Readiness: Unknown: no readiness result on record"],
] as const)("the dialog words the readiness of %s and never shows UNKNOWN as ready", async (_name, readiness, tone, text) => {
  asAdmin();
  if (readiness) {
    const state = "state" in readiness ? readiness.state : "DONE";
    api.runs.mockResolvedValue([{ runId: "readiness-opaque", kind: "READINESS", state }]);
    api.detail.mockResolvedValue(run({ runId: "readiness-opaque", kind: "READINESS", state, step: state, outcome: readiness.outcome, checks: [
      { phase: "pre", checkNo: 1, title: "Cluster", member: "Member 1", status: "PASS", result: "PASS", summary: "Active", blocking: true, observedAt: "2026-10-06T09:00:00Z" },
      ...("warn" in readiness ? [{ phase: "pre", checkNo: 8, title: "Traffic rate", member: "Member 1", status: "WARN", result: "WARN", summary: "Low", blocking: false, observedAt: "2026-10-06T09:00:00Z" }] : [])] }));
  }
  // A unit read while its pre-checks run offers no failover: the verdict wording is checked on the helper for that case.
  if (readiness && "state" in readiness) {
    expect(failoverReadinessVerdict(unit, await api.detail())).toEqual({ tone, text: text.replace("Readiness: ", "") });
    return;
  }
  await open(); fireEvent.click(screen.getByRole("button", { name: "Start failover" }));
  const dialog = await screen.findByRole("dialog");
  const verdict = dialog.querySelector("[data-readiness-verdict]")!;
  expect(verdict).toHaveAttribute("data-readiness-verdict", tone);
  expect(within(dialog).getByText(text)).toBeInTheDocument();
  if (tone === "unknown") expect(verdict).not.toHaveTextContent(/Readiness: Ready/);
  expect(verdict).toHaveTextContent(/The run repeats every pre-check before it switches/);
});
it("a server refusal is worded as sent and never as ready, whatever the readiness run says", () => {
  const ready = run({ kind: "READINESS", state: "DONE", step: "DONE", outcome: "READY" });
  expect(failoverReadinessVerdict({ ...unit, refusalReason: "OPEN_INCIDENT" }, ready)).toEqual({ tone: "blocked", text: "Refused: OPEN_INCIDENT" });
  expect(failoverReadinessVerdict(unit, run({ kind: "READINESS", state: "DONE", step: "DONE", outcome: "NOT_READY", message: "CLUSTER_NOT_HEALTHY",
    checks: [{ phase: "pre", checkNo: 1, title: "Cluster", member: "Member 1", status: "FAIL", result: "FAIL", summary: "Down", blocking: true }] as never })))
    .toEqual({ tone: "blocked", text: "Not ready · 1 blocker", detail: "CLUSTER_NOT_HEALTHY" });
});
it("dialog cancellation sends no request", async () => {
  asAdmin(); await open(); await warning(); fireEvent.click(screen.getByRole("button", { name: "Cancel" })); expect(api.approve).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled();
});
it("operation admin picks 30 / 60 / 120 minutes, waits for distinct approval and starts without another warning", async () => {
  const pending = { ...approval, policy: "OPERATION_ADMIN_TWO_PERSON", approved: false };
  api.approve.mockResolvedValue(pending); api.approvals.mockResolvedValueOnce([]).mockResolvedValue([pending]);
  await open(); await warning("change CHG-200 planned failover");
  const dialog = screen.getByRole("dialog");
  expect(dialog.querySelector("[data-failover-policy]")).toHaveAttribute("data-failover-policy", "two-person");
  expect(within(dialog).queryByLabelText(/Window ends/)).toBeNull(); expect(dialog.querySelector("input[type='datetime-local']")).toBeNull();
  expect(within(dialog).getAllByRole("radio").map(r => (r as HTMLInputElement).value)).toEqual(["30", "60", "120"]);
  expect(within(dialog).getByRole("radio", { name: "60 minutes" })).toBeChecked();
  expect(within(dialog).getByText(/A second administrator must agree before anything is sent/)).toBeInTheDocument();
  expect(within(dialog).queryByText(/Valid for 60 minutes/)).toBeNull();
  fireEvent.click(within(dialog).getByRole("radio", { name: "120 minutes" }));
  fireEvent.click(screen.getByRole("button", { name: "Request failover" }));
  expect(await screen.findByText("Awaiting second approval")).toBeInTheDocument(); expect(api.start).not.toHaveBeenCalled();
  expect(Date.parse(api.approve.mock.calls[0][2]) - Date.parse(api.approve.mock.calls[0][1])).toBe(2 * HOUR);
  expect(api.approve.mock.calls[0][3]).toBe("change CHG-200 planned failover");
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  api.approvals.mockResolvedValue([{ ...pending, approved: true }]);
  fireEvent.click(screen.getByRole("button", { name: "CLS-ROMEO-01" })); fireEvent.click(screen.getByRole("button", { name: "CLS-ROMEO-01" }));
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1)); expect(screen.queryByRole("dialog")).toBeNull();
});
it("second approver submits only the displayed request revision", async () => {
  api.approvals.mockResolvedValue([{ ...approval, policy: "OPERATION_ADMIN_TWO_PERSON", approved: false, canStart: false, canApprove: true, executionNonce: undefined }]);
  setup(); fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
  fireEvent.click(await screen.findByRole("button", { name: "Approve request revision 1" }));
  await waitFor(() => expect(api.second).toHaveBeenCalledWith(expect.objectContaining({ unitId: unit.unitId }), approval.requestId, 1)); expect(api.start).not.toHaveBeenCalled();
});
it("refresh discovers the same active run without submitting", async () => {
  api.runs.mockResolvedValue([run()]); const view = setup(); fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
  expect(await screen.findByText(/Run: run-opaque/)).toBeInTheDocument(); view.unmount(); setup(); fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
  expect(await screen.findByText(/Run: run-opaque/)).toBeInTheDocument(); expect(api.approve).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled();
});
it("displays all recorded phases and explicit evidence coverage", () => {
  const checks = ["pre", "post", "post_return"].map(phase => ({ phase, checkNo: 8, title: "Traffic rate", member: "Member 1", status: "PASS", result: "PASS", summary: "Observed", blocking: true }));
  render(<FailoverRun unit={unit} run={run({ state: "DONE", step: "DONE", checks })} />); expect(screen.getByText(/Evidence coverage:/)).toHaveTextContent("3 / 3"); expect(screen.getByText(/Evidence coverage:/)).toHaveTextContent("Final post-checks: Recorded");
});
it("an uncertain HTTP result retries the same revision without another warning", async () => {
  api.start.mockRejectedValueOnce({ body: { code: "NETWORK_UNCERTAIN" } }).mockResolvedValue({ runId: "run-opaque" });
  api.approvals.mockResolvedValueOnce([]).mockResolvedValue([approval]);
  asAdmin(); await open(); await warning(); fireEvent.click(screen.getByRole("button", { name: "Fail over now" }));
  expect(await screen.findAllByText("NETWORK_UNCERTAIN")).toHaveLength(2);
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  fireEvent.click(screen.getByRole("button", { name: "CLS-ROMEO-01" }));
  fireEvent.click(screen.getByRole("button", { name: "CLS-ROMEO-01" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Start failover" })).toBeEnabled());
  fireEvent.click(screen.getByRole("button", { name: "Start failover" }));
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(2));
  expect(api.start.mock.calls[1]).toEqual(api.start.mock.calls[0]); expect(api.approve).toHaveBeenCalledTimes(1);
});
const finishedReadiness = (observedAt: string, runId = "readiness-opaque") => run({ runId, kind: "READINESS", state: "DONE", step: "DONE", outcome: "READY",
  checks: [{ phase: "pre", checkNo: 1, title: "Cluster", member: "Member 1", status: "PASS", result: "PASS", summary: "Active", blocking: true, observedAt }] });
const readinessSummary = { runId: "readiness-opaque", kind: "READINESS", state: "DONE" };
it("mounting with the latest readiness already on screen loads the unit list once", async () => {
  api.runs.mockResolvedValue([readinessSummary]); api.detail.mockResolvedValue(finishedReadiness("2026-10-06T08:59:00Z"));
  render(<ThemeProvider theme={m3Theme}><CpFailoverPanel memberDeviceId="opaque-1" initialUnitId={unit.unitId} /></ThemeProvider>);
  await waitFor(() => expect(api.detail).toHaveBeenCalled()); await waitFor(() => expect(screen.getByRole("button", { name: "Start failover" })).toBeEnabled());
  await new Promise(resolve => setTimeout(resolve, 50));
  expect(api.units).toHaveBeenCalledTimes(1);
});
it("a readiness run newer than the listed one refreshes the unit list exactly once", async () => {
  api.runs.mockResolvedValue([readinessSummary]); api.detail.mockResolvedValue(finishedReadiness("2026-10-06T09:30:00Z"));
  render(<ThemeProvider theme={m3Theme}><CpFailoverPanel memberDeviceId="opaque-1" initialUnitId={unit.unitId} /></ThemeProvider>);
  await waitFor(() => expect(api.units).toHaveBeenCalledTimes(2));
  await new Promise(resolve => setTimeout(resolve, 50));
  expect(api.units).toHaveBeenCalledTimes(2); expect(api.units).toHaveBeenNthCalledWith(2, "opaque-1", "check_point");
});

// P0-3: workers write phase "post_return"; the panel used to filter "final" and showed the final checks as not evaluated.
it.each(["post_return", "final"] as const)("final post-checks recorded under phase %s are shown, not reported missing", phase => {
  const checks = [
    { phase: "pre", checkNo: 1, title: "Cluster state", member: "Member 1", status: "PASS", result: "PASS", summary: "Active", blocking: true },
    { phase, checkNo: 9, title: "State synchronization", member: "Member 1", status: "PASS", result: "PASS", summary: "Sync OK after return", blocking: true },
  ];
  render(<FailoverRun unit={unit} run={run({ state: "DONE", step: "DONE", checks })} />);
  expect(screen.getByText("Sync OK after return")).toBeInTheDocument();
  expect(screen.getAllByText("Not evaluated")).toHaveLength(1);
  expect(screen.getByText(/Evidence coverage:/)).toHaveTextContent("2 / 2");
  expect(screen.getByText(/Evidence coverage:/)).toHaveTextContent("Final post-checks: Recorded");
});
it("shows the unit's recovery mode and its open incident with the role note", async () => {
  api.units.mockResolvedValue([{ ...unit, recoveryMode: "PRIMARY_UP", refusalReason: "OPEN_INCIDENT" }]);
  api.incidents.mockResolvedValue({ canRelease: false, roleNote: "Only a security_admin can release a failover incident.", incidents: [
    { incidentRef: "run-opaque", clusterId: unit.clusterId, unitId: unit.unitId, cluster_member_ref: "CLS-ROMEO-01", reason: "MUTATION_STOPPED",
      openedAt: "2026-10-20T10:00:00Z", incidentActive: true, blocksEstate: false, unresolvedSends: 0, members: [] }] });
  setup();
  expect(await screen.findByText(/Recovery mode: Primary Up/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "CLS-ROMEO-01" }));
  expect(await screen.findByText("Only a security_admin can release a failover incident.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Release incident" })).toBeNull();
});
it("a stopped run with a confirmed DOWN offers Resume return step on the unit panel, which requests it once", async () => {
  api.runs.mockResolvedValue([{ runId: "run-opaque", kind: "FAILOVER", state: "STOPPED" }]);
  api.detail.mockResolvedValue(run({ state: "STOPPED", step: "POSTCHECK", mutationPossible: true, incidentRef: "run-opaque",
    message: "TRAFFIC_BELOW_TOLERANCE", kind: "FAILOVER", resume: { eligible: true, refusal: null, canResume: true,
      businessHoursInside: false, businessHours: "08:00–20:00", formerActiveRef: "opaque-1" } }));
  api.resume.mockResolvedValue({ runId: "run-opaque", resumeId: "resume-opaque", state: "QUEUED" });
  setup(); fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
  fireEvent.click(await screen.findByRole("button", { name: "Resume return step" }));
  fireEvent.change(screen.getByLabelText(/Work log note/), { target: { value: "Return the former active" } });
  fireEvent.click(screen.getAllByRole("button", { name: "Resume return step" }).at(-1)!);
  await waitFor(() => expect(api.resume).toHaveBeenCalledWith("run-opaque", "run-opaque", "Return the former active", false));
});
it("a session whose policy is not known is told both outcomes, and the server's answer decides", async () => {
  api.incidents.mockRejectedValue({ status: 500, body: {} });
  await open(); await warning("change CHG-300");
  const dialog = screen.getByRole("dialog");
  expect(dialog.querySelector("[data-failover-policy]")).toHaveAttribute("data-failover-policy", "unknown");
  expect(within(dialog).getByText(/unless your role needs a second administrator to agree first/)).toBeInTheDocument();
  fireEvent.click(within(dialog).getByRole("button", { name: "Request failover" }));
  // The server answered ADMIN_SINGLE and approved: the run starts as it always did.
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1));
  expect(Date.parse(api.approve.mock.calls[0][2]) - Date.parse(api.approve.mock.calls[0][1])).toBe(HOUR);
});
it("a request the dialog said needs a second administrator never starts on the first confirm, even if the server needed none", async () => {
  await open(); await warning("change CHG-400");
  const dialog = screen.getByRole("dialog");
  expect(within(dialog).getByText(/A second administrator must agree before anything is sent/)).toBeInTheDocument();
  fireEvent.click(within(dialog).getByRole("button", { name: "Request failover" }));
  expect(await within(dialog).findByRole("alert")).toHaveTextContent("No second administrator was needed for this request, so it is already approved on the server. The failover has not been started. Confirm again to start it now.");
  expect(api.start).not.toHaveBeenCalled();
  await new Promise(resolve => setTimeout(resolve, 50)); expect(api.start).not.toHaveBeenCalled();
  fireEvent.click(within(dialog).getByRole("button", { name: "Request failover" }));
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1)); expect(api.approve).toHaveBeenCalledTimes(1);
});
it("cancelling after an unneeded second-administrator warning leaves no confirmation: the next row click shows the dialog and starts nothing", async () => {
  await open(); await warning("change CHG-401");
  const dialog = screen.getByRole("dialog");
  fireEvent.click(within(dialog).getByRole("button", { name: "Request failover" }));
  expect(await within(dialog).findByRole("alert")).toBeInTheDocument();
  expect(JSON.parse(sessionStorage.getItem("failover-request:check_point:opaque-cluster")!).confirmedRevision).toBeUndefined();
  fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  // The server now lists the approved request, as the next read would.
  api.approvals.mockResolvedValue([approval]);
  fireEvent.click(screen.getByRole("button", { name: "Start failover" }));
  expect(await screen.findByRole("dialog")).toBeInTheDocument();
  await new Promise(resolve => setTimeout(resolve, 50)); expect(api.start).not.toHaveBeenCalled();
});
it("a remembered request shows its note and its end in Turkey time, and is sent again unchanged", async () => {
  sessionStorage.setItem("failover-request:check_point:opaque-cluster", JSON.stringify({ requestId: "00000000-0000-4000-8000-000000000009",
    from: "2099-10-06T08:00:00.000Z", until: "2099-10-06T09:30:00.000Z", reason: "Pilot" }));
  asAdmin(); await open(); fireEvent.click(screen.getByRole("button", { name: "Start failover" }));
  const note = await screen.findByLabelText(/Work log note/);
  expect(note).toHaveValue("Pilot"); expect(note).toBeDisabled();
  expect(screen.getByText("This request was already made. Valid until 2099-10-06 12:30 GMT+3.")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Fail over now" }));
  await waitFor(() => expect(api.approve).toHaveBeenCalledTimes(1));
  expect(api.approve.mock.calls[0].slice(1)).toEqual(["2099-10-06T08:00:00.000Z", "2099-10-06T09:30:00.000Z", "Pilot", "00000000-0000-4000-8000-000000000009"]);
});
it.each([
  [{ status: 403, body: { error: "ACTION_MAPPING_REQUIRED" } }, "ACTION_MAPPING_REQUIRED (HTTP 403)"],
  [{ status: 403, body: { error: "ACTION_REFUSED" } }, "ACTION_REFUSED (HTTP 403)"],
  [{ status: 409, body: { code: "OPEN_INCIDENT", error: "ignored" } }, "OPEN_INCIDENT (HTTP 409)"],
  [{ status: 500, body: {} }, "Request failed (HTTP 500)"],
])("a refused start names the server's reason and HTTP status (%#)", async (failure, text) => {
  api.approve.mockRejectedValue(failure);
  asAdmin(); await open(); await warning("Pilot");
  fireEvent.click(screen.getByRole("button", { name: "Fail over now" }));
  expect((await screen.findAllByText(text)).length).toBeGreaterThan(0);
  // The refusal stays in the dialog, word for word, and nothing was started.
  expect(within(screen.getByRole("dialog")).getByRole("alert")).toHaveTextContent(text);
  expect(screen.queryByText("Request failed")).toBeNull(); expect(api.start).not.toHaveBeenCalled();
});
it("lists the VSX chassis unit first with its own pre-checks and no failover action, then each Virtual System", async () => {
  const chassis = { ...unit, unitKind: "VSX_CHASSIS" as const, failoverRefusal: "VSX_CHASSIS_MODE_UNKNOWN", refusalReason: "VSX_CHASSIS_COMMANDS_NOT_APPROVED" };
  const vs = { ...unit, unitId: "opaque-vs-13", unitKind: "VIRTUAL_SYSTEM" as const, virtual_system: "VS-ROMEO-13" };
  api.units.mockResolvedValue([vs, chassis]);
  api.readiness.mockResolvedValue({ runId: "run-chassis" });
  setup();
  const first = await screen.findByRole("button", { name: "VSX chassis · CLS-ROMEO-01" });
  const second = screen.getByRole("button", { name: "Virtual System · VS-ROMEO-13" });
  expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  fireEvent.click(first);
  await waitFor(() => expect(screen.getByRole("button", { name: "Run pre-checks" })).toBeEnabled());
  // The chassis offers no failover; its Virtual System carries the action on its own row, without being opened.
  const chassisCard = first.closest("[data-unit-kind='VSX_CHASSIS']") as HTMLElement, vsCard = second.closest("[data-unit-kind='VIRTUAL_SYSTEM']") as HTMLElement;
  expect(within(chassisCard).queryByRole("button", { name: /Fail over|Start failover/ })).toBeNull();
  expect(within(vsCard).getByRole("button", { name: "Start failover" })).toBeEnabled();
  expect(screen.getByText(/VSX chassis mode not known/)).toHaveAttribute("title", "VSX_CHASSIS_MODE_UNKNOWN");
  fireEvent.click(screen.getByRole("button", { name: "Run pre-checks" }));
  await waitFor(() => expect(api.readiness).toHaveBeenCalledWith(expect.objectContaining({ unitId: "opaque-cluster" })));
  fireEvent.click(first);
  fireEvent.click(second);
  await waitFor(() => expect(screen.getByRole("button", { name: "Start failover" })).toBeEnabled());
});

// ---- VSX chassis (PO_DECISION_RECORD_2026_11_23_VSX_VS0_INDEPENDENT.md, RATIFIED): VS0 and every VS are independent ----
const chassisUnit = (overrides = {}) => ({ ...unit, maskedName: "CLS-TANGO-04", unitKind: "VSX_CHASSIS" as const, chassisMode: "HA" as const,
  chassisAction: "FAILOVER" as const, chassisStartRefusal: null, sequenceAction: "FAILOVER_ALL_VIRTUAL_SYSTEMS" as const,
  sequenceStartRefusal: null, ...overrides });
const vsUnit = { ...unit, unitId: "opaque-vs-12", unitKind: "VIRTUAL_SYSTEM" as const, virtual_system: "VS-TANGO-12", maskedName: "VS-TANGO-12" };
it.each(["HA", "VSLS"] as const)("offers three actions in %s mode: Fail over VS0, each VS's own, and all virtual systems", async chassisMode => {
  api.units.mockResolvedValue([chassisUnit({ chassisMode }), vsUnit]); setup();
  const chassisCard = (await screen.findByRole("button", { name: "VSX chassis · CLS-TANGO-04" })).closest("[data-unit-kind='VSX_CHASSIS']") as HTMLElement;
  const vsCard = screen.getByRole("button", { name: "Virtual System · VS-TANGO-12" }).closest("[data-unit-kind='VIRTUAL_SYSTEM']") as HTMLElement;
  await waitFor(() => expect(within(chassisCard).getByRole("button", { name: "Fail over VS0" })).toBeEnabled());
  const all = within(chassisCard).getByRole("button", { name: "Fail over all virtual systems" });
  expect(all).toBeEnabled();
  expect(all.closest("[data-failover-action]")).toHaveAttribute("title", expect.stringContaining("one after another"));
  expect(within(vsCard).getByRole("button", { name: "Start failover" })).toBeEnabled();
  expect(within(vsCard).queryByRole("button", { name: /all virtual systems|VS0/ })).toBeNull();
  expect(within(chassisCard).getByText(/VS0 is its own failover unit/)).toHaveAttribute("data-chassis-action", "FAILOVER");
  expect(within(chassisCard).getByText(/VS0 is its own failover unit/)).toHaveTextContent(chassisMode === "HA" ? "VSX HA mode" : "VSLS mode");
});
it("requests the VS0 run as the chassis unit's own failover, without the sequence operation", async () => {
  asAdmin(); api.units.mockResolvedValue([chassisUnit({ chassisMode: "VSLS" })]); setup();
  await waitFor(() => expect(screen.getByRole("button", { name: "Fail over VS0" })).toBeEnabled());
  fireEvent.click(screen.getByRole("button", { name: "Fail over VS0" }));
  expect(await screen.findByText(/VS0 fails over on its own/)).toBeInTheDocument();
  expect(screen.queryByText(/every virtual system must/)).toBeNull();
  fireEvent.change(await screen.findByLabelText(/Work log note/), { target: { value: "change CHG-123 VS0" } });
  fireEvent.click(screen.getByRole("button", { name: "Fail over now" }));
  await waitFor(() => expect(api.approve).toHaveBeenCalledTimes(1));
  expect(api.approve.mock.calls[0]).toHaveLength(5);
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1));
});
it("requests \"Fail over all virtual systems\" with its own operation and says the runs follow one another", async () => {
  asAdmin(); api.units.mockResolvedValue([chassisUnit()]); setup();
  await waitFor(() => expect(screen.getByRole("button", { name: "Fail over all virtual systems" })).toBeEnabled());
  fireEvent.click(screen.getByRole("button", { name: "Fail over all virtual systems" }));
  expect(await screen.findByText(/stops at the first virtual system that does not succeed/)).toHaveTextContent("never in parallel");
  expect(within(screen.getByRole("dialog")).getByText(/Every virtual system's latest pre-checks are ready/)).toBeInTheDocument();
  fireEvent.change(await screen.findByLabelText(/Work log note/), { target: { value: "change CHG-123 all VS" } });
  fireEvent.click(screen.getByRole("button", { name: "Fail over now" }));
  await waitFor(() => expect(api.approve).toHaveBeenCalledTimes(1));
  expect(api.approve.mock.calls[0][5]).toBe("FAILOVER_ALL_VIRTUAL_SYSTEMS");
});
it("shows a running sequence and disables a second start, but not the VS0 run", async () => {
  api.units.mockResolvedValue([chassisUnit({ chassisMode: "VSLS",
    sequence: { sequenceId: "seq-opaque", state: "RUNNING", outcome: null, total: 3, started: 2, vsIds: ["1", "2", "3"], currentRunId: "run-opaque",
      failedVsId: null, requestedAt: "2026-11-19T20:00:00Z", finishedAt: null } })]); setup();
  expect(await screen.findByText(/virtual system 2 of 3 running/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "VSX chassis · CLS-TANGO-04" }));
  await waitFor(() => expect(api.runs).toHaveBeenCalled());
  expect(screen.getByRole("button", { name: "Fail over all virtual systems" })).toBeDisabled();
});
it("a virtual system not ready refuses only the sequence; the chassis's own readiness refuses only VS0", async () => {
  api.units.mockResolvedValue([chassisUnit({ sequenceStartRefusal: "VIRTUAL_SYSTEM_NOT_READY" })]); const first = setup();
  fireEvent.click(await screen.findByRole("button", { name: "VSX chassis · CLS-TANGO-04" }));
  await waitFor(() => expect(api.runs).toHaveBeenCalled());
  expect(screen.getByText(/All virtual systems not now: A virtual system of this chassis is not ready/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Fail over all virtual systems" })).toBeDisabled();
  await waitFor(() => expect(screen.getByRole("button", { name: "Fail over VS0" })).toBeEnabled());
  first.unmount();
  api.units.mockResolvedValue([chassisUnit({ chassisStartRefusal: "VSX_CHASSIS_READINESS_NOT_READY" })]); setup();
  fireEvent.click(await screen.findByRole("button", { name: "VSX chassis · CLS-TANGO-04" }));
  await waitFor(() => expect(api.runs).toHaveBeenCalled());
  expect(screen.getByText(/VS0 not now: The chassis pre-checks are not ready/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Fail over VS0" })).toBeDisabled();
  await waitFor(() => expect(screen.getByRole("button", { name: "Fail over all virtual systems" })).toBeEnabled());
});
it("with the VS0 mode unknown offers no VS0 run but keeps \"Fail over all virtual systems\"", async () => {
  api.units.mockResolvedValue([chassisUnit({ chassisMode: "UNKNOWN", chassisAction: null, failoverRefusal: "VSX_CHASSIS_MODE_UNKNOWN" })]); setup();
  await waitFor(() => expect(screen.getByRole("button", { name: "Fail over all virtual systems" })).toBeEnabled());
  expect(screen.queryByRole("button", { name: "Fail over VS0" })).toBeNull();
  expect(screen.getByText(/VSX chassis mode not known/)).toHaveAttribute("title", "VSX_CHASSIS_MODE_UNKNOWN");
});
it("warns when the directory is unreachable at start and retries the same approval when Retry is clicked", async () => {
  api.start.mockRejectedValueOnce({ status: 409, body: { code: "DIRECTORY_UNREACHABLE_AT_START" } }).mockResolvedValue({ runId: "run-opaque" });
  asAdmin(); await open(); await warning();
  fireEvent.click(screen.getByRole("button", { name: "Fail over now" }));
  expect((await screen.findAllByText(/DIRECTORY_UNREACHABLE_AT_START/)).length).toBeGreaterThan(0);
  const retryButtons = screen.getAllByRole("button", { name: "Retry" });
  expect(retryButtons.length).toBeGreaterThan(0);
  fireEvent.click(retryButtons[0]);
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(2));
  expect(api.approve).toHaveBeenCalledTimes(1);
  expect(api.start.mock.calls[1]).toEqual(api.start.mock.calls[0]);
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
});

it.each([
  ["cisco_asa", "Cisco ASA"],
  ["fortinet", "FortiGate"],
] as const)("shows 'Failover execution not enabled for %s' instead of an execution button, skips approvals and offers Run pre-checks", async (vendor, label) => {
  api.units.mockResolvedValue([{ ...unit, vendor, canApprove: false, canStart: false, canSchedule: false, canRunReadiness: true,
    failoverRefusal: "FAILOVER_VENDOR_NOT_AVAILABLE" }]);
  api.readiness.mockResolvedValue({ runId: "run-readiness" });
  render(<ThemeProvider theme={m3Theme}><CpFailoverPanel memberDeviceId="opaque-1" vendor={vendor} /></ThemeProvider>);
  expect(await screen.findByLabelText(`${label} failover`)).toBeInTheDocument();
  expect(screen.getByText(`Failover execution not enabled for ${label}`)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "CLS-ROMEO-01" }));
  await waitFor(() => expect(api.runs).toHaveBeenCalled());
  expect(api.approvals).not.toHaveBeenCalled();
  expect(screen.queryByRole("button", { name: /Start failover|Fail over/ })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Run pre-checks" }));
  await waitFor(() => expect(api.readiness).toHaveBeenCalledWith(expect.objectContaining({ unitId: unit.unitId, vendor })));
});

// ---- VSX chassis refusal and stop codes in plain words (the code stays on hover or beside the words) ----
it.each([
  ["VSX_CHASSIS_MODE_CHANGED", "The chassis mode changed since the approval: request a new one"],
  ["VSX_CHASSIS_MODE_VSLS_USE_SEQUENCE", "VSLS chassis: fail over all virtual systems instead"],
  ["VIRTUAL_SYSTEM_NOT_READY", "A virtual system of this chassis is not ready"],
  ["SEQUENCE_ALREADY_RUNNING", "All virtual systems are already being failed over"],
  ["SEQUENCE_SCHEDULE_NOT_SUPPORTED", "Failing over all virtual systems cannot be scheduled"],
  ["VS_RUN_ACTIVE", "A virtual system failover is open on this cluster"],
  ["CHASSIS_RUN_ACTIVE", "A chassis run is open on this cluster"],
])("a refused chassis request %s reads in words and keeps the code and status", (code, words) => {
  expect(errorText({ status: 409, body: { code } })).toBe(`${words} (${code} (HTTP 409))`);
});
it("a non-chassis refusal keeps the bare code and status", () => {
  expect(errorText({ status: 409, body: { code: "OPEN_INCIDENT" } })).toBe("OPEN_INCIDENT (HTTP 409)");
});
it.each([
  ["VSX_VIRTUAL_SYSTEM_NOT_MOVED", "Stopped: a virtual system did not move with the chassis"],
  ["VSX_VIRTUAL_SYSTEM_NOT_RETURNED", "Stopped: a virtual system did not settle after the return"],
  ["VSX_VIRTUAL_SYSTEM_NOT_ON_ACTIVE_MEMBER", "Stopped before the switch: a virtual system is not on the chassis's active member"],
])("a stopped chassis run %s shows its stop in words with the code on hover, never as verified", (message, words) => {
  render(<FailoverRun unit={unit} run={run({ state: "STOPPED", step: "POSTCHECK", mutationPossible: true, message, incidentRef: "incident-opaque" })} />);
  expect(screen.getByText(words)).toHaveAttribute("title", message);
  expect(screen.queryByText(message)).toBeNull();
  expect(screen.getByRole("status")).not.toHaveTextContent(/^Failover verified$/);
  expect(screen.queryByText(/Evidence coverage/)).toBeNull();
});
it("a run stopped by an unknown code shows the code as sent", () => {
  render(<FailoverRun unit={unit} run={run({ state: "STOPPED", step: "POSTCHECK", mutationPossible: false, message: "SOME_FUTURE_CODE" })} />);
  expect(screen.getByText("SOME_FUTURE_CODE")).toBeInTheDocument();
});
it("shows the stopped sequence with its failed VSID in words", async () => {
  api.units.mockResolvedValue([chassisUnit({ chassisMode: "VSLS", chassisAction: "FAILOVER_ALL_VIRTUAL_SYSTEMS",
    sequence: { sequenceId: "seq-opaque", state: "STOPPED", outcome: "VIRTUAL_SYSTEM_RUN_NOT_SUCCEEDED", total: 3, started: 2, vsIds: ["1", "2", "3"], currentRunId: null,
      failedVsId: "2", requestedAt: "2026-11-19T20:00:00Z", finishedAt: "2026-11-19T20:10:00Z" } })]); setup();
  expect(await screen.findByText(/Stopped: a virtual system run did not succeed \(VSID 2\)/)).toHaveAttribute("data-sequence-state", "STOPPED");
  expect(screen.queryByText(/virtual system 3 of 3/)).toBeNull();
});
