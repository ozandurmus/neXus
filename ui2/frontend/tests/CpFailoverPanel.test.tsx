import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { CpFailoverPanel, runStep } from "../src/screens/CpFailoverPanel";

const api = vi.hoisted(() => ({ units: vi.fn(), approvals: vi.fn(), runs: vi.fn(), detail: vi.fn(), readiness: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({
  listCpFailoverUnits: api.units, listCpFailoverApprovals: api.approvals,
  listCpFailoverRuns: api.runs, getCpFailoverRun: api.detail, runCpFailoverReadiness: api.readiness,
}));

const cluster = { clusterId: "cluster-opaque", unitId: "cluster-opaque", cluster_member_ref: "CLS-ROMEO-01", canApprove: false, canStart: true, canSchedule: true };
const vs = { ...cluster, unitId: "vs-opaque", virtual_system: "VS-ROMEO-01-07" };
const renderPanel = () => render(<ThemeProvider theme={m3Theme}><CpFailoverPanel memberDeviceId="member-device-id" /></ThemeProvider>);

beforeEach(() => {
  api.units.mockReset().mockResolvedValue([cluster, vs]);
  api.approvals.mockReset().mockResolvedValue([]);
  api.runs.mockReset().mockResolvedValue([]);
  api.detail.mockReset();
  api.readiness.mockReset().mockResolvedValue({ runId: "readiness-run" });
});

describe("Check Point failover", () => {
  it.each([
    ["PLANNED", "PLANNED", -1], ["PRECHECK", "PRECHECK", 0], ["FAILING_OVER", "FAILING_OVER", 1],
    ["SWITCHED", "SWITCHED", 2], ["POSTCHECK", "POSTCHECK", 3], ["RETURNING", "RETURNING", 3], ["DONE", "DONE", 4],
    ...["PLANNED", "PRECHECK", "FAILING_OVER", "SWITCHED", "POSTCHECK", "RETURNING"].map((step, i) => ["STOPPED", step, Math.max(0, Math.min(i - 1, 3))]),
  ] as Array<[Parameters<typeof runStep>[0], string, number]>) ("maps %s at %s to step %i", (state, step, expected) => {
    expect(runStep(state, step)).toBe(expected);
  });

  it("nests VS units, disables actions without a window, and excludes raw check data", async () => {
    api.runs.mockImplementation(async (unit) => unit.unitId === vs.unitId ? [{ runId: "run-opaque" }] : []);
    api.detail.mockResolvedValue({ runId: "run-opaque", state: "STOPPED", step: "POSTCHECK", failedCheck: "6",
      checks: [{ checkNo: 6, phase: "post", status: "FAIL", derived: "192.0.2.19 SECRET-DEVICE" },
        { checkNo: 9, phase: "pre", status: "PASS", derived: "{}" },
        { checkNo: 10, phase: "post", status: "PASS", derived: "{}" }] });
    renderPanel();
    fireEvent.click(await screen.findByRole("button", { name: /Virtual System · VS-ROMEO-01-07/ }));
    expect(screen.getByText("Stopped: Connections")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Failover now" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Schedule" })).toBeDisabled();
    expect(screen.queryByText(/SECRET-DEVICE/)).toBeNull();
    expect(within(screen.getByRole("table", { name: "Failover checks" })).getByText("Connections")).toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Failover checks" })).getByText("State synchronization")).toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Failover checks" })).getByText("Installed policy parity")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "CLS-ROMEO-01" }));
    expect(screen.queryByRole("table", { name: "Failover checks" })).toBeNull();
  });

  it("hides approval and start controls when server flags deny them", async () => {
    api.units.mockResolvedValue([{ ...cluster, canApprove: false, canStart: false, canSchedule: false }]);
    renderPanel();
    fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
    expect(screen.queryByRole("button", { name: "Approve a window" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Failover now" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Schedule" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Run pre-checks" })).toBeNull();
  });

  it("shows Run pre-checks only when the server allows starting collections", async () => {
    api.units.mockResolvedValue([cluster]);
    renderPanel();
    fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
    expect(screen.getByRole("button", { name: "Run pre-checks" })).toBeEnabled();
  });

  it("allows a future approved window for scheduling while keeping immediate failover disabled", async () => {
    api.units.mockResolvedValue([cluster]);
    api.approvals.mockResolvedValue([{ approvalId: "a", windowFrom: new Date(Date.now() + 3600_000).toISOString(),
      windowUntil: new Date(Date.now() + 7200_000).toISOString(), reason: "Drill", approvedBy: "actor-opaque", revokedAt: null }]);
    renderPanel();
    fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
    expect(await screen.findByText(/Approved from/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Failover now" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Schedule" })).toBeEnabled();
  });
  it("shows PAN pair checks without virtual-system units", async () => {
    api.units.mockResolvedValue([{ ...cluster, vendor: "palo_alto" }]);
    api.runs.mockResolvedValue([{ runId: "pan-run" }]);
    api.detail.mockResolvedValue({ runId: "pan-run", state: "STOPPED", step: "POSTCHECK", failedCheck: "6",
      checks: [5, 6, 7].map(checkNo => ({ checkNo, phase: "post", status: "UNKNOWN", derived: "{}" })) });
    render(<ThemeProvider theme={m3Theme}><CpFailoverPanel memberDeviceId="member-device-id" vendor="palo_alto" /></ThemeProvider>);
    fireEvent.click(await screen.findByRole("button", { name: "CLS-ROMEO-01" }));
    expect(await screen.findByText("Stopped: Sessions carried")).toBeInTheDocument();
    for (const label of ["Session synchronization", "Sessions carried", "Version parity"])
      expect(within(screen.getByRole("table", { name: "Failover checks" })).getByText(label)).toBeInTheDocument();
    expect(screen.queryByText(/Virtual System/)).toBeNull();
  });
});
