import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { CpFailoverPanel, FailoverRun, runStep } from "../src/screens/CpFailoverPanel";
import type { CpFailoverRunDetail } from "../src/auth/adminApi";
const api = vi.hoisted(() => ({ units: vi.fn(), approvals: vi.fn(), runs: vi.fn(), detail: vi.fn(), readiness: vi.fn(), approve: vi.fn(), second: vi.fn(), start: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({ listCpFailoverUnits: api.units, listCpFailoverApprovals: api.approvals,
  listCpFailoverRuns: api.runs, getCpFailoverRun: api.detail, runCpFailoverReadiness: api.readiness,
  approveCpFailover: api.approve, secondApproveCpFailover: api.second, startCpFailover: api.start }));
const unit = { clusterId: "opaque-cluster", unitId: "opaque-cluster", cluster_member_ref: "CLS-ROMEO-01", vendor: "check_point" as const,
  mode: "HA", readinessObservedAt: "2026-10-06T09:00:00Z", canApprove: true, canStart: true, canSchedule: false, masked: false,
  members: [{ device_id: "opaque-1", hostname: "FW-ROMEO-01", ha_role: "ACTIVE" }, { device_id: "opaque-2", hostname: "FW-ROMEO-02", ha_role: "STANDBY" }] };
const approval = { approvalId: "00000000-0000-4000-8000-000000000001", requestId: "00000000-0000-4000-8000-000000000001", revision: 1, policy: "ADMIN_SINGLE", approved: true,
  executionNonce: "nonce-opaque", canStart: true, windowFrom: new Date(Date.now()-60_000).toISOString(), windowUntil: new Date(Date.now()+3_600_000).toISOString(), revokedAt: null };
const run = (overrides = {}): CpFailoverRunDetail => ({ runId: "run-opaque", approvalId: "00000000-0000-4000-8000-000000000001", scheduledFor: "", state: "PRECHECK", step: "PRECHECK",
  outcome: null, failedCheck: null, message: null, steps: [], checks: [], mutationPossible: false, ...overrides });
function setup() { return render(<ThemeProvider theme={m3Theme}><CpFailoverPanel memberDeviceId="opaque-1" /></ThemeProvider>); }
async function open() { setup(); fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" })); await waitFor(() => expect(screen.getByRole("button", { name: "Start failover" })).toBeEnabled()); }
async function warning() {
  fireEvent.click(screen.getByRole("button", { name: "Start failover" }));
  fireEvent.change(screen.getByLabelText("Window ends"), { target: { value: "2099-10-06T12:00" } });
}
beforeEach(() => {
  vi.resetAllMocks(); vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000001"); sessionStorage.clear(); api.units.mockResolvedValue([unit]); api.approvals.mockResolvedValue([]); api.runs.mockResolvedValue([]);
  api.approve.mockResolvedValue(approval); api.start.mockResolvedValue({ runId: "run-opaque" }); api.detail.mockResolvedValue(run());
});
it.each(["check_point", "palo_alto"] as const)("empty %s lookup has no actions", async vendor => {
  api.units.mockResolvedValue([]); render(<CpFailoverPanel memberDeviceId="opaque-1" vendor={vendor} />);
  expect(await screen.findByText("No eligible failover units")).toBeInTheDocument(); expect(screen.queryByRole("button")).toBeNull();
});
it.each([
  ["PLANNED", "PLANNED", 0, "Queued"], ["PRECHECK", "PRECHECK", 0, "Checking readiness"],
  ["FAILING_OVER", "FAILING_OVER", 1, "Failing over"], ["SWITCHED", "SWITCHED", 2, "Role change confirmed"],
  ["POSTCHECK", "POSTCHECK", 3, "Checking traffic and cluster after switch"], ["RETURNING", "RETURNING", 4, "Returning former active to standby"],
  ["POSTCHECK", "FINAL_POSTCHECK", 5, "Verifying final cluster health"], ["DONE", "DONE", 6, "Failover verified"],
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
it("one warning starts an admin request with its revision and nonce, and ignores duplicate clicks", async () => {
  await open(); await warning(); expect(screen.getByText(/Traffic may be interrupted/)).toBeInTheDocument();
  const ok = screen.getByRole("button", { name: "OK / Start" }); fireEvent.click(ok); fireEvent.click(ok);
  await waitFor(() => expect(api.start).toHaveBeenCalledTimes(1)); expect(api.approve).toHaveBeenCalledTimes(1);
  expect(api.start).toHaveBeenCalledWith(expect.objectContaining({ unitId: unit.unitId }), { requestId: approval.requestId, revision: 1, executionNonce: approval.executionNonce, warningConfirmed: true });
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull()); expect(screen.queryByText("Awaiting second approval")).toBeNull();
});
it("dialog cancellation sends no request", async () => {
  await open(); await warning(); fireEvent.click(screen.getByRole("button", { name: "Cancel" })); expect(api.approve).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled();
});
it("operation admin waits for distinct approval and starts without another warning", async () => {
  const pending = { ...approval, policy: "OPERATION_ADMIN_TWO_PERSON", approved: false };
  api.approve.mockResolvedValue(pending); api.approvals.mockResolvedValueOnce([]).mockResolvedValue([pending]);
  await open(); await warning(); fireEvent.click(screen.getByRole("button", { name: "OK / Start" }));
  expect(await screen.findByText("Awaiting second approval")).toBeInTheDocument(); expect(api.start).not.toHaveBeenCalled();
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
  const checks = ["pre", "post", "final"].map(phase => ({ phase, checkNo: 8, title: "Traffic rate", member: "Member 1", status: "PASS", result: "PASS", summary: "Observed", blocking: true }));
  render(<FailoverRun unit={unit} run={run({ state: "DONE", step: "DONE", checks })} />); expect(screen.getByText(/Evidence coverage:/)).toHaveTextContent("3 / 3"); expect(screen.getByText(/Evidence coverage:/)).toHaveTextContent("Final post-checks: Recorded");
});
it("an uncertain HTTP result retries the same revision without another warning", async () => {
  api.start.mockRejectedValueOnce({ body: { code: "NETWORK_UNCERTAIN" } }).mockResolvedValue({ runId: "run-opaque" });
  api.approvals.mockResolvedValueOnce([]).mockResolvedValue([approval]);
  await open(); await warning(); fireEvent.click(screen.getByRole("button", { name: "OK / Start" }));
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
