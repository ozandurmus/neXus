import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { CpFailoverRunDetail, CpFailoverUnit } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", () => ({ resumeCpFailoverReturn: vi.fn(), approveCpFailoverResume: vi.fn() }));
const { FailoverRunView } = await import("../src/screens/failover/FailoverRunView");

afterEach(cleanup);
const unit = { clusterId: "c", unitId: "c", cluster_member_ref: "CLS-ROMEO-01", vendor: "check_point" as const, canApprove: true, canStart: true, canSchedule: false, masked: false,
  members: [{ device_id: "m1", hostname: "FW-ROMEO-01", ha_role: "ACTIVE" }, { device_id: "m2", hostname: "FW-ROMEO-02", ha_role: "STANDBY" }] } as CpFailoverUnit;
const run = (over: Partial<CpFailoverRunDetail> = {}): CpFailoverRunDetail => ({ runId: "run-opaque", approvalId: "a", scheduledFor: "2026-11-23T09:00:00Z", state: "STOPPED", step: "PRECHECK",
  outcome: null, failedCheck: null, message: "COMMAND_GATE_UNAVAILABLE", steps: [], checks: [], mutationPossible: false, ...over });
const post = (status: "PASS" | "FAIL", no: number) => ["m1", "m2"].map((device, i) => ({ checkNo: no, phase: "post", status, result: status, title: `Post ${no}`, member: `Member ${i + 1}`,
  device_id: device, hostname: i === 0 ? "FW-ROMEO-01" : "FW-ROMEO-02", cluster_member_ref: "CLS-ROMEO-01", summary: "x", blocking: true, derived: "{}", observedAt: "2026-11-23T09:05:00Z" }));

it("stopped before anything was sent: says so, shows the code, no resume block and no empty phase groups", () => {
  const { container } = render(<FailoverRunView unit={unit} run={run({ resume: { eligible: false, refusal: "RESUME_RUN_NOT_ELIGIBLE", canResume: false, businessHoursInside: false, businessHours: "08:00-20:00" } })} />);
  expect(screen.getByRole("status")).toHaveTextContent("Stopped before anything was sent");
  expect(screen.getByText("Nothing was sent to a device.")).toBeInTheDocument();
  expect(screen.getByText(/Command gate unavailable · COMMAND_GATE_UNAVAILABLE|COMMAND_GATE_UNAVAILABLE/)).toBeInTheDocument();
  expect(screen.queryByLabelText("Resume return step")).toBeNull();
  expect(screen.queryByText(/Resume is not available/)).toBeNull();
  expect(container.querySelectorAll("[data-run-group]").length).toBe(0);
  expect(screen.queryByText("Not evaluated")).toBeNull();
  expect(screen.getByText("Nothing more is sent.")).toBeInTheDocument();
});

it("shows six steps in plain words and the run id only under Technical details", () => {
  const { container } = render(<FailoverRunView unit={unit} run={run({ state: "POSTCHECK", step: "POSTCHECK", message: null })} />);
  for (const step of ["Pre-checks", "Switch", "Roles confirmed", "Post-checks", "Return", "Final checks"]) expect(screen.getAllByText(step).length).toBeGreaterThan(0);
  expect(screen.getByRole("status")).toHaveTextContent(/^Failing over/);
  const details = container.querySelector("details:last-of-type") as HTMLElement;
  expect(details).toHaveTextContent("Technical details");
  expect(details).toHaveTextContent("Run: run-opaque");
  expect(screen.getAllByText(/Session continuity: not evaluated/)).toHaveLength(1);
});

it("DONE shows the outcome card with the roles, the required checks and no session-continuity claim", () => {
  const { container } = render(<FailoverRunView unit={unit} run={run({ state: "DONE", step: "DONE", outcome: "SUCCESS", message: null, lastConfirmedRoles: { m1: "STANDBY", m2: "ACTIVE" },
    checks: [...post("PASS", 1), ...post("PASS", 2)] as never })} />);
  const card = container.querySelector("[data-run-outcome='verified']") as HTMLElement;
  expect(card).toHaveTextContent("Failover verified · roles now FW-ROMEO-01 standby, FW-ROMEO-02 active");
  expect(card).toHaveTextContent("4 of 4 required checks passed · session continuity not evaluated");
  expect(container.querySelector("[data-run-group='post']")).toHaveTextContent("2 passed, 0 failed");
});

it("stopped after the switch names the incident and offers the resume button when the server allows it", () => {
  render(<FailoverRunView unit={unit} run={run({ mutationPossible: true, incidentRef: "incident-opaque", message: "POSTCHECK_FAILED", lastConfirmedRoles: { m1: "DOWN" },
    resume: { eligible: true, refusal: null, canResume: true, businessHoursInside: false, businessHours: "08:00-20:00", formerActiveRef: "m1", peerRef: "m2" },
    checks: post("FAIL", 8) as never })} />);
  expect(screen.getByRole("status")).toHaveTextContent("Stopped after the switch - action needed");
  expect(screen.getByText(/Incident: incident-opaque/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Resume return step" })).toBeInTheDocument();
  expect(screen.getByText(/Failed or unknown check: Post 8 · \w+ · FW-ROMEO-01, FW-ROMEO-02/)).toBeInTheDocument();
});

it("an unknown mutation state is worded as unknown, never as nothing sent", () => {
  render(<FailoverRunView unit={unit} run={run({ mutationPossible: undefined })} />);
  expect(screen.getByRole("status")).toHaveTextContent("it is not known whether anything was sent");
  expect(screen.queryByText("Nothing was sent to a device.")).toBeNull();
});
