import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReadinessCard } from "../src/screens/ReadinessChecksTable";
import type { ReadinessCheck } from "../src/auth/adminApi";

const members = [
  { device_id: "opaque-1", hostname: "FW-ROMEO-01-M1", ha_role: "ACTIVE" },
  { device_id: "opaque-2", hostname: "FW-ROMEO-01-M2", ha_role: "STANDBY" },
];
const checks: ReadinessCheck[] = [
  ...members.map((member, i) => ({ checkNo: 1, title: "Cluster state", member: `Member ${i + 1}`, device_id: member.device_id,
    result: "PASS" as const, summary: i ? "Standby" : "Active", blocking: true })),
  ...[5, 13].flatMap(no => members.map((member, i) => ({ checkNo: no, title: no === 5 ? "ARP" : "Last failover", member: `Member ${i + 1}`, device_id: member.device_id,
    result: "UNKNOWN" as const, summary: "Observation unavailable", blocking: false }))),
];
const props = { checks, members, cluster: "CLS-ROMEO-01", vendor: "check_point", observedAt: new Date(Date.now() - 120_000).toISOString(), canRun: true, onRun: vi.fn() };

describe("Readiness card", () => {
  it("shows ready, three counts and one secondary action, with a single row per check", () => {
    render(<ReadinessCard {...props} status="READY" masked />);
    expect(screen.getByText("Ready")).toBeInTheDocument();
    for (const label of ["Passed: 1", "Blocking: 0", "Info: 2"]) expect(screen.getByLabelText(label)).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("row")).toHaveLength(4);
    expect(within(table).getAllByRole("columnheader").map(cell => cell.textContent)).toEqual(["Check", "FW-ROMEO-01-M1 · active", "FW-ROMEO-01-M2 · standby"]);
    expect(screen.getAllByText("info")).toHaveLength(2);
    expect(screen.getByText("AIView Pseudonymized")).toBeInTheDocument();
    expect(screen.getByText("Failover runs re-check everything at start.")).toBeInTheDocument();
    expect(table.textContent).not.toMatch(/[{}]/);
    expect(screen.getAllByRole("button")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Run pre-checks" }));
    expect(props.onRun).toHaveBeenCalledOnce();
  });

  it("counts a failed check once even when both members fail and tints only its row", () => {
    render(<ReadinessCard {...props} status="NOT_READY" checks={checks.map(check => ({ ...check, result: "FAIL" }))} />);
    expect(screen.getByText("Not ready · 1 blocker")).toBeInTheDocument();
    expect(screen.getByLabelText("Blocking: 1")).toBeInTheDocument();
    expect(screen.getByLabelText("Passed: 0")).toBeInTheDocument();
    expect(screen.getByLabelText("Info: 2")).toBeInTheDocument();
    expect(screen.getAllByRole("img", { name: "Blocking" })).toHaveLength(1);
    const failing = screen.getByText("Cluster state").closest("tr")!;
    const informational = screen.getByText("ARP").closest("tr")!;
    expect(failing.className).not.toEqual(informational.className);
  });

  it("keeps unknown evidence unknown and does not shift a missing member's result", () => {
    render(<ReadinessCard {...props} status="UNKNOWN" checks={[{ ...checks[1], member: "Member 1" }]} />);
    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(screen.getByLabelText("Passed: 0")).toBeInTheDocument();
    const cells = within(screen.getByText("Cluster state").closest("tr")!).getAllByRole("cell");
    expect(cells.map(cell => cell.textContent)).toEqual(["Not collected", "Standby"]);
  });

  it("shows no observations and follows the server masking flag across session changes", () => {
    const { rerender } = render(<ReadinessCard {...props} checks={[]} masked />);
    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(screen.getByText("No observations yet")).toBeInTheDocument();
    expect(screen.getByText("AIView Pseudonymized")).toBeInTheDocument();
    rerender(<ReadinessCard {...props} checks={[]} masked={false} />);
    expect(screen.queryByText("AIView Pseudonymized")).toBeNull();
  });
});
