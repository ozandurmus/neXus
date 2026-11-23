import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import type { ReadinessCheck, ReadinessMember } from "../src/auth/adminApi";
import { ChecksByConsequence } from "../src/screens/failover/ChecksByConsequence";

afterEach(cleanup);
const AT = "2026-11-20T09:00:00Z";
const members: ReadinessMember[] = [{ device_id: "m1", hostname: "FW-ROMEO-01", ha_role: "active" }, { device_id: "m2", hostname: "FW-ROMEO-02", ha_role: "standby" }];
type Result = ReadinessCheck["result"];
const pair = (no: number, title: string, results: [Result, Result], summaries: [string, string] | string, blocking = true, derived: unknown = {}): ReadinessCheck[] => {
  const [a, b] = typeof summaries === "string" ? [summaries, summaries] : summaries;
  return (["m1", "m2"] as const).map((device, i) => ({ checkNo: no, title, member: i === 0 ? "Member 1" : "Member 2", device_id: device, result: results[i],
    summary: i === 0 ? a : b, blocking, derived, observedAt: AT }));
};
const checks: ReadinessCheck[] = [
  ...pair(1, "Cluster state", ["PASS", "PASS"], ["Active", "Standby"], true, { role: "ACTIVE" }).map((c, i) => ({ ...c, derived: { role: i === 0 ? "ACTIVE" : "STANDBY" } })),
  ...pair(2, "Cluster IP table", ["PASS", "PASS"], "Tables match"),
  ...pair(4, "Configuration sync", ["PASS", "PASS"], "Config sync enabled"),
  ...pair(5, "Session synchronization", ["UNKNOWN", "UNKNOWN"], "Session sync fields missing or not recognised"),
  ...pair(6, "Connections", ["NOT_EVALUATED", "NOT_EVALUATED"], "Count unavailable, Count unavailable, below tolerance"),
  ...pair(9, "State synchronization", ["PASS", "FAIL"], ["Sync OK, 0 lost updates", "Sync status: Lost; +3 lost updates since 2026-11-23T03:00:24.520360Z; baseline kept"]),
  ...pair(10, "Traffic and ARP", ["PASS", "PASS"], "123 ARP entries", false),
  ...pair(11, "Last failover", ["WARN", "WARN"], "5 h ago", false),
];
const view = (extra = {}) => render(<ChecksByConsequence checks={checks} members={members} vendor="check_point" observedAt={AT} {...extra} />);

it("lists the blocker first, then the unknown group, then warnings", () => {
  const { container } = view();
  expect([...container.querySelectorAll("section[data-check-group]")].map(n => n.getAttribute("data-check-group"))).toEqual(["blocking", "unknown", "warnings"]);
  const blocking = container.querySelector("[data-check-group='blocking']") as HTMLElement;
  expect(within(blocking).getByText("State synchronization")).toBeInTheDocument();
  expect(within(blocking).getByText("Blocking (1)")).toBeInTheDocument();
});

it("the group counts add up to the number of checks", () => {
  const { container } = view();
  expect(container.querySelector("[data-check-counts]")).toHaveTextContent("8 checks: 1 blocking · 2 unknown · 1 warnings · 3 passed · 1 information");
  const counts = ["Blocking (1)", "Unknown / not evaluated (2)", "Warnings (1)", "Passed (3)", "Information (1)"];
  for (const text of counts) expect(container).toHaveTextContent(text);
});

it("an unknown or not-evaluated blocking check is its own group and says it is not a pass", () => {
  const { container } = view();
  const unknown = container.querySelector("[data-check-group='unknown']") as HTMLElement;
  expect(unknown).toHaveTextContent(/not a pass/);
  expect(within(unknown).getByText("Session synchronization")).toBeInTheDocument();
  expect(within(unknown).getByText("Not evaluated - session continuity is not measured")).toBeInTheDocument();
  expect(container.querySelector("[data-check-group='passed']")).not.toHaveTextContent("Connections");
});

it("one merged 'Both members' cell when the members say the same; two cells when they differ", () => {
  const { container } = view();
  const unknown = container.querySelector("[data-check-group='unknown']") as HTMLElement;
  expect(within(unknown).getAllByText("Both members").length).toBe(2);
  const blocker = container.querySelector("[data-check-entry='9']") as HTMLElement;
  expect(within(blocker).queryByText("Both members")).toBeNull();
  expect(blocker).toHaveTextContent("FW-ROMEO-01 · active");
  expect(blocker).toHaveTextContent("FW-ROMEO-02 · standby");
});

it("only the failing cell carries the tint attribute", () => {
  const { container } = view();
  const blocker = container.querySelector("[data-check-entry='9']") as HTMLElement;
  const cells = blocker.querySelectorAll("[data-member-cell]");
  expect([...cells].map(c => c.hasAttribute("data-failed"))).toEqual([false, true]);
  expect(container.querySelectorAll("[data-failed]").length).toBe(1);
});

it("the headline is short; the counters sit behind Details, with machine times in display time", () => {
  const { container } = view();
  const blocker = container.querySelector("[data-check-entry='9']") as HTMLElement;
  expect(blocker).toHaveTextContent("Sync status: Lost");
  expect(blocker.querySelector("[data-check-detail]")).toBeNull();
  fireEvent.click(within(blocker).getByRole("button", { name: "Details" }));
  const detail = blocker.querySelector("[data-check-detail]")!;
  expect(detail).toHaveTextContent("+3 lost updates since 2026-11-23 06:00 GMT+3");
  expect(detail).toHaveTextContent("baseline kept");
  expect(detail.textContent).not.toMatch(/T03:00/);
});

it("renders the server's row action (the PAN config sync) under its check, wherever the check sits", () => {
  const failing = checks.map(c => c.checkNo === 4 && c.device_id === "m2" ? { ...c, result: "FAIL" as const, summary: "Config sync is OFF on this member" } : c);
  const { container } = render(<ChecksByConsequence checks={failing} members={members} vendor="palo_alto" observedAt={AT}
    rowAction={(no, failed) => no === 4 ? <button>{failed ? "Sync configuration to passive" : "no action"}</button> : null} />);
  const entry = container.querySelector("[data-check-entry='4']") as HTMLElement;
  expect(within(entry).getByRole("button", { name: "Sync configuration to passive" })).toBeInTheDocument();
  expect(screen.getAllByRole("button", { name: "Sync configuration to passive" })).toHaveLength(1);
});

it("opens the passed checks as the two-column table", () => {
  const { container } = view();
  const passed = container.querySelector("details[data-check-group='passed']") as HTMLElement;
  expect(passed.querySelector("summary")).toHaveTextContent("Passed (3): Cluster state, Cluster IP table, Configuration sync");
  expect(within(passed).getByRole("table", { name: "Readiness results" })).toBeInTheDocument();
});

it("says so when there are no observations", () => {
  render(<ChecksByConsequence checks={[]} members={members} />);
  expect(screen.getByText("No observations yet")).toBeInTheDocument();
});
