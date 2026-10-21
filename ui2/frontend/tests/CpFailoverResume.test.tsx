import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ResumeReturn, resumeStep } from "../src/screens/CpFailoverPanel";
import type { CpFailoverResume, CpFailoverRunDetail } from "../src/auth/adminApi";

/** "Resume return step" on the failover panel (docs/design/CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md §6). */
const api = vi.hoisted(() => ({ resume: vi.fn(), approve: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({ resumeCpFailoverReturn: api.resume, approveCpFailoverResume: api.approve,
  approveCpFailover: vi.fn(), getCpFailoverRun: vi.fn(), listCpFailoverApprovals: vi.fn(), listCpFailoverRuns: vi.fn(),
  listCpFailoverUnits: vi.fn(), secondApproveCpFailover: vi.fn(), startCpFailover: vi.fn(), runCpFailoverReadiness: vi.fn(),
  listFailoverIncidents: vi.fn(), releaseFailoverIncident: vi.fn() }));
const unit = { clusterId: "opaque-cluster", unitId: "opaque-cluster", cluster_member_ref: "CLS-ROMEO-01", maskedName: "CLS-ROMEO-01",
  vendor: "check_point" as const, canApprove: true, canStart: true, canSchedule: false, masked: false,
  members: [{ device_id: "opaque-1", hostname: "FW-ROMEO-01", ha_role: "DOWN" }, { device_id: "opaque-2", hostname: "FW-ROMEO-02", ha_role: "ACTIVE" }] };
const eligible: CpFailoverResume = { eligible: true, refusal: null, canResume: true, businessHoursInside: false,
  businessHours: "08:00–20:00", formerActiveRef: "opaque-1" };
const run = (resume: CpFailoverResume | undefined, overrides: Partial<CpFailoverRunDetail> = {}): CpFailoverRunDetail => ({
  runId: "run-opaque", approvalId: "approval-opaque", scheduledFor: "", state: "STOPPED", step: "POSTCHECK", outcome: "OUTCOME_UNKNOWN",
  failedCheck: "8", message: "TRAFFIC_BELOW_TOLERANCE", steps: [], checks: [], kind: "FAILOVER", mutationPossible: true,
  incidentRef: "run-opaque", resume, ...overrides });
const show = (detail: CpFailoverRunDetail, view: typeof unit = unit) => {
  const changed = vi.fn();
  render(<ThemeProvider theme={m3Theme}><ResumeReturn run={detail} unit={view} onChanged={changed} /></ThemeProvider>);
  return changed;
};
beforeEach(() => { vi.resetAllMocks(); api.resume.mockResolvedValue({ runId: "run-opaque", resumeId: "resume-opaque", state: "QUEUED" }); });

it("offers the button to an allowed role and confirms with the contract text, reason and incident id", async () => {
  const changed = show(run(eligible));
  fireEvent.click(screen.getByRole("button", { name: "Resume return step" }));
  expect(screen.getByText("Resume the return step of this failover run?")).toBeInTheDocument();
  expect(screen.getByText(/Run run-opaque on CLS-ROMEO-01 stopped after clusterXL_admin down \(TRAFFIC_BELOW_TOLERANCE\)\. FW-ROMEO-01 is down by admin and FW-ROMEO-02 carries the traffic\./)).toBeInTheDocument();
  expect(screen.getByText(/Only if FW-ROMEO-02 is Active, FW-ROMEO-01 is Down and the cluster is in Active Up mode, it sends clusterXL_admin up once to FW-ROMEO-01\. Then it waits up to 60 s/)).toBeInTheDocument();
  expect(screen.getByText("Nothing is retried. If anything does not match, neXus stops and the incident stays open.")).toBeInTheDocument();
  // Outside business hours: no acknowledgement is shown.
  expect(screen.queryByRole("checkbox")).toBeNull();
  const confirm = screen.getAllByRole("button", { name: "Resume return step" }).at(-1)!;
  expect(confirm).toBeDisabled();
  fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "  Return the former active  " } });
  fireEvent.click(confirm); fireEvent.click(confirm);
  await waitFor(() => expect(api.resume).toHaveBeenCalledTimes(1));
  expect(api.resume).toHaveBeenCalledWith("run-opaque", "run-opaque", "Return the former active", false);
  await waitFor(() => expect(changed).toHaveBeenCalled());
});

it("inside business hours the request needs its own acknowledgement", async () => {
  show(run({ ...eligible, businessHoursInside: true }));
  fireEvent.click(screen.getByRole("button", { name: "Resume return step" }));
  fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Return" } });
  const confirm = screen.getAllByRole("button", { name: "Resume return step" }).at(-1)!;
  expect(confirm).toBeDisabled();
  fireEvent.click(screen.getByRole("checkbox", { name: /I acknowledge this runs during business hours \(08:00–20:00 Europe\/Istanbul\)/ }));
  expect(confirm).toBeEnabled();
  fireEvent.click(confirm);
  await waitFor(() => expect(api.resume).toHaveBeenCalledWith("run-opaque", "run-opaque", "Return", true));
});

it("operator and aiview see state only; a refusal is shown in plain words", () => {
  show(run({ ...eligible, canResume: false }));
  expect(screen.queryByRole("button", { name: "Resume return step" })).toBeNull();
  expect(screen.getByText("Only a security_admin or operation_admin can resume the return step.")).toBeInTheDocument();
  document.body.innerHTML = "";
  show(run(eligible), { ...unit, masked: true });
  expect(screen.queryByRole("button")).toBeNull();
  document.body.innerHTML = "";
  show(run({ ...eligible, eligible: false, canResume: false, refusal: "RESUME_DOWN_NOT_CONFIRMED" }));
  expect(screen.getByText("Resume is not available: neXus did not confirm that this run took the member down.")).toBeInTheDocument();
  expect(screen.queryByRole("button")).toBeNull();
});

it("a server refusal on confirm is shown in plain words and nothing else is requested", async () => {
  api.resume.mockRejectedValue({ body: { code: "INCIDENT_MISMATCH" } });
  show(run(eligible));
  fireEvent.click(screen.getByRole("button", { name: "Resume return step" }));
  fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Return" } });
  fireEvent.click(screen.getAllByRole("button", { name: "Resume return step" }).at(-1)!);
  expect(await screen.findByText(/the incident changed since this page was loaded/)).toBeInTheDocument();
  expect(api.resume).toHaveBeenCalledTimes(1);
});

it("a second administrator approves a pending two-person resume with the same text", async () => {
  api.approve.mockResolvedValue({ runId: "run-opaque", resumeId: "resume-opaque", state: "QUEUED" });
  show(run({ ...eligible, eligible: false, canResume: false, refusal: "RESUME_ALREADY_USED", resumeId: "resume-opaque",
    state: "PENDING_APPROVAL", step: "PENDING_APPROVAL", policy: "OPERATION_ADMIN_TWO_PERSON", canApprove: true, businessHoursInside: true }));
  expect(screen.getByRole("status")).toHaveTextContent("Resume awaiting second approval");
  fireEvent.click(screen.getByRole("button", { name: "Approve resume" }));
  expect(screen.getByText(/FW-ROMEO-01 is down by admin and FW-ROMEO-02 carries the traffic/)).toBeInTheDocument();
  expect(screen.queryByLabelText("Reason")).toBeNull();
  fireEvent.click(screen.getByRole("checkbox"));
  fireEvent.click(screen.getAllByRole("button", { name: "Approve resume" }).at(-1)!);
  await waitFor(() => expect(api.approve).toHaveBeenCalledWith("run-opaque", "resume-opaque", true));
});

it.each([
  ["READING_STATE", "READING_STATE", 0, "Reading cluster state"], ["RETURNING", "RETURNING", 1, "Returning former active to standby"],
  ["POST_RETURN", "POST_RETURN", 2, "Verifying final cluster health"], ["DONE", "DONE", 3, "Return verified"],
] as const)("renders the resume stepper %s", (state, step, index, label) => {
  expect(resumeStep(state, step)).toBe(index);
  show(run({ ...eligible, canResume: false, refusal: "RESUME_ALREADY_USED", resumeId: "resume-opaque", state, step }));
  expect(screen.getByRole("status")).toHaveTextContent(label);
  expect(screen.queryByRole("button")).toBeNull();
  if (state === "DONE") expect(screen.getByText("The incident stays open until a security_admin releases it.")).toBeInTheDocument();
});

it("a stopped resume names its stop in plain words and offers nothing more", () => {
  show(run({ ...eligible, canResume: false, refusal: "RESUME_ALREADY_USED", resumeId: "resume-opaque", state: "STOPPED",
    step: "READING_STATE", message: "RESUME_MEMBER_NOT_DOWN", outcome: "RESUME_MEMBER_NOT_DOWN" }));
  expect(resumeStep("STOPPED", "READING_STATE")).toBe(0);
  expect(screen.getByRole("status")).toHaveTextContent("the former active is no longer down");
  expect(screen.getByText(/The incident stays open; recovery continues out of band/)).toBeInTheDocument();
  expect(screen.queryByRole("button")).toBeNull();
});

it("shows nothing for a run that is not a stopped failover run", () => {
  show(run(eligible, { state: "DONE" }));
  expect(screen.queryByLabelText("Resume return step")).toBeNull();
  show(run(undefined));
  expect(screen.queryByLabelText("Resume return step")).toBeNull();
});
