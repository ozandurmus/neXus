import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { CpFailoverRun, CpFailoverUnit } from "../src/auth/adminApi";

const api = vi.hoisted(() => ({ detail: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({ getCpFailoverRun: api.detail, resumeCpFailoverReturn: vi.fn(), approveCpFailoverResume: vi.fn() }));
const { FailoverHistory, runOutcomeWord } = await import("../src/screens/failover/FailoverHistory");

const unit = { clusterId: "c", unitId: "c", cluster_member_ref: "CLS-ROMEO-01", vendor: "check_point", canApprove: true, canStart: true, canSchedule: false, masked: false, members: [] } as unknown as CpFailoverUnit;
const run = (n: number, over: Partial<CpFailoverRun> = {}): CpFailoverRun => ({ runId: `run-${n}`, approvalId: "a", scheduledFor: `2026-11-${String(10 + n).padStart(2, "0")}T09:00:00Z`,
  state: "DONE", step: "DONE", outcome: "SUCCESS", failedCheck: null, message: null, steps: [], kind: "FAILOVER", ...over });
beforeEach(() => { api.detail.mockReset(); });
afterEach(cleanup);

it("lists failover runs newest first as given, ten rows, then Show all", () => {
  const runs = Array.from({ length: 12 }, (_, i) => run(i + 1));
  render(<FailoverHistory runs={runs} unit={unit} />);
  expect(document.querySelectorAll("[data-history-run]")).toHaveLength(10);
  fireEvent.click(screen.getByRole("button", { name: "Show all (12)" }));
  expect(document.querySelectorAll("[data-history-run]")).toHaveLength(12);
});

it("keeps pre-check runs behind a toggle and words each outcome", () => {
  render(<FailoverHistory runs={[run(1, { state: "STOPPED", outcome: null, message: "OPEN_INCIDENT" }), run(2, { kind: "READINESS" })]} unit={unit} />);
  expect(document.querySelectorAll("[data-history-run]")).toHaveLength(1);
  expect(screen.getByText("Failover")).toBeInTheDocument();
  expect(runOutcomeWord(run(1, { state: "STOPPED", outcome: null, message: "OPEN_INCIDENT" }))).toBe("Open failover incident");
  expect(runOutcomeWord(run(3))).toBe(runOutcomeWord(run(4)));
  fireEvent.click(screen.getByRole("button", { name: "Show pre-check runs (1)" }));
  expect(document.querySelectorAll("[data-history-run]")).toHaveLength(2);
  expect(screen.getByText("Pre-check run")).toBeInTheDocument();
});

it("Open run reads that run's detail and shows it under its row, without offering to act on it", async () => {
  api.detail.mockResolvedValue({ ...run(1, { state: "STOPPED", outcome: "STOPPED", mutationPossible: true, incidentRef: "incident-opaque", message: "POSTCHECK_FAILED",
    resume: { eligible: true, refusal: null, canResume: true, businessHoursInside: false, businessHours: "08:00-20:00" } }), checks: [] });
  render(<FailoverHistory runs={[run(1), run(2)]} unit={unit} />);
  fireEvent.click(within(document.querySelector("[data-history-run='run-1']") as HTMLElement).getByRole("button", { name: "Open run" }));
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Stopped after the switch - action needed"));
  expect(api.detail).toHaveBeenCalledWith("run-1", "check_point");
  expect(screen.queryByRole("button", { name: "Resume return step" })).toBeNull();
});

it("says so when there is no earlier run", () => {
  render(<FailoverHistory runs={[]} unit={unit} />);
  expect(screen.getByText("No earlier failover runs of this unit.")).toBeInTheDocument();
});
