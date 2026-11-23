import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReadinessCard } from "../src/screens/ReadinessChecksTable";
import type { ReadinessCheck } from "../src/auth/adminApi";
import { m3 } from "../src/theme/m3Theme";

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
    expect(within(table).getAllByRole("columnheader").map(cell => cell.textContent)).toEqual(["Check", "FW-ROMEO-01-M1 · active (inventory)", "FW-ROMEO-01-M2 · standby (inventory)"]);
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

  it("names the member with config sync off: its own cell is red, the peer's cell is neutral, the row stays FAIL", () => {
    const sync = (i: number, memberSync: "off" | "on" | "unknown", summary: string): ReadinessCheck => ({ checkNo: 4, title: "Configuration sync", member: `Member ${i + 1}`,
      device_id: members[i].device_id, result: "FAIL", summary, blocking: true, memberSync });
    const off = "Config sync is OFF on this member (Device > High Availability > General > Setup > Enable Config Sync)";
    const on = "Config sync enabled on this member; its peer has it off";
    render(<ReadinessCard {...props} vendor="palo_alto" status="NOT_READY" checks={[sync(0, "on", on), sync(1, "off", off)]} />);
    expect(screen.getByText("Not ready · 1 blocker")).toBeInTheDocument();
    const row = screen.getByText("Configuration sync").closest("tr")!;
    expect(screen.getAllByRole("img", { name: "Blocking" })).toHaveLength(1);
    const cells = within(row).getAllByRole("cell");
    expect(cells.map(cell => cell.textContent)).toEqual([on, off]);
    expect(cells.map(cell => cell.getAttribute("data-member-sync"))).toEqual(["on", "off"]);
    const red = getComputedStyle(cells[1]).backgroundColor;
    expect(red).not.toBe("");
    expect(getComputedStyle(cells[0]).backgroundColor).not.toBe(red);
    expect(getComputedStyle(row).backgroundColor).not.toBe(red);
  });

  it("keeps an unknown config sync member neutral and old evidence red on the whole row", () => {
    const unknown: ReadinessCheck = { checkNo: 4, title: "Configuration sync", member: "Member 1", device_id: members[0].device_id,
      result: "FAIL", summary: "Config sync setting of this member is not known; its peer has it off", blocking: true, memberSync: "unknown" };
    const { unmount } = render(<ReadinessCard {...props} vendor="palo_alto" status="NOT_READY"
      checks={[unknown, { ...unknown, member: "Member 2", device_id: members[1].device_id, memberSync: "off" }]} />);
    expect(within(screen.getByText("Configuration sync").closest("tr")!).getAllByRole("cell").map(cell => cell.getAttribute("data-member-sync")))
      .toEqual(["unknown", "off"]);
    unmount();
    const old = (i: number): ReadinessCheck => ({ checkNo: 4, title: "Configuration sync", member: `Member ${i + 1}`, device_id: members[i].device_id,
      result: "FAIL", summary: "Config sync is disabled on 1 member(s): the running configurations are not kept equal", blocking: true });
    render(<ReadinessCard {...props} vendor="palo_alto" status="NOT_READY" checks={[old(0), old(1)]} />);
    const row = screen.getByText("Configuration sync").closest("tr")!;
    expect(within(row).getAllByRole("cell").every(cell => !cell.hasAttribute("data-member-sync"))).toBe(true);
  });

  it("shows a VSX chassis VS0 policy warning as an amber, non-blocking row (PO 2026-11-23)", () => {
    const text = "Same VS0 policy name; install times differ by 62 days -- reinstall the VSX gateway policy to align";
    const warn: ReadinessCheck[] = members.map((member, i) => ({ checkNo: 10, title: "Installed policy parity", member: `Member ${i + 1}`,
      device_id: member.device_id, result: "WARN" as const, summary: text, blocking: false }));
    render(<ReadinessCard {...props} status="READY" checks={[...checks, ...warn]} />);
    expect(screen.getByText("Ready · 1 warning")).toBeInTheDocument();
    for (const label of ["Passed: 1", "Blocking: 0", "Info: 2"]) expect(screen.getByLabelText(label)).toBeInTheDocument();
    const row = screen.getByText("Installed policy parity").closest("tr")!;
    expect(row.getAttribute("data-row-tone")).toBe("warning");
    expect(within(row).getByRole("img", { name: "Warning" })).toBeInTheDocument();
    expect(within(row).getByText("warning")).toBeInTheDocument();
    expect(within(row).getAllByText(text)).toHaveLength(2);
    expect(screen.getAllByText("info")).toHaveLength(2);
    expect(screen.queryAllByRole("img", { name: "Blocking" })).toHaveLength(0);
  });

  it("a failing member outweighs a warning on the other member", () => {
    const mixed: ReadinessCheck[] = members.map((member, i) => ({ checkNo: 10, title: "Installed policy parity", member: `Member ${i + 1}`,
      device_id: member.device_id, result: i ? "FAIL" as const : "WARN" as const, summary: "x", blocking: i === 1 }));
    render(<ReadinessCard {...props} status="NOT_READY" checks={[...checks, ...mixed]} />);
    const row = screen.getByText("Installed policy parity").closest("tr")!;
    expect(row.getAttribute("data-row-tone")).toBe("blocking");
    expect(screen.getByLabelText("Blocking: 1")).toBeInTheDocument();
  });

  it("shows a Palo Alto idle-pair session sync warning amber, not red, and the badge names it (PO 2026-11-23)", () => {
    const warning = "No sessions to synchronize (0 active sessions on both members); the session sync path is not proven by traffic.";
    const panChecks: ReadinessCheck[] = [
      ...members.map((member, i) => ({ checkNo: 1, title: "HA mode and roles", member: `Member ${i + 1}`, device_id: member.device_id,
        result: "PASS" as const, summary: i ? "Passive" : "Active", blocking: true })),
      ...members.map((member, i) => ({ checkNo: 5, title: "Session synchronization", member: `Member ${i + 1}`, device_id: member.device_id,
        result: "WARN" as const, summary: warning, blocking: true })),
    ];
    render(<ReadinessCard {...props} vendor="palo_alto" status="READY" checks={panChecks} />);
    expect(screen.queryByText("Ready")).toBeNull();
    expect(screen.getByText("Ready · 1 warning")).toBeInTheDocument();
    for (const label of ["Passed: 1", "Blocking: 0", "Info: 0"]) expect(screen.getByLabelText(label)).toBeInTheDocument();
    const row = screen.getByText("Session synchronization").closest("tr")!;
    expect(row).toHaveAttribute("data-row-tone", "warning");
    expect(row).toHaveStyle({ backgroundColor: m3.warningContainer });
    expect(row).not.toHaveStyle({ backgroundColor: m3.errorContainer });
    expect(within(row).getByRole("img", { name: "Warning" })).toBeInTheDocument();
    expect(screen.queryAllByRole("img", { name: "Blocking" })).toHaveLength(0);
    expect(within(row).getAllByText(warning)).toHaveLength(2);
    expect(screen.getByText("HA mode and roles").closest("tr")).not.toHaveAttribute("data-row-tone");
  });

  it("keeps a warning row red when the other member's session sync failed", () => {
    const mixed: ReadinessCheck[] = members.map((member, i) => ({ checkNo: 5, title: "Session synchronization", member: `Member ${i + 1}`,
      device_id: member.device_id, result: i ? "FAIL" as const : "WARN" as const, summary: "x", blocking: true }));
    render(<ReadinessCard {...props} vendor="palo_alto" status="NOT_READY" checks={mixed} />);
    expect(screen.getByText("Not ready · 1 blocker")).toBeInTheDocument();
    expect(screen.getByText("Session synchronization").closest("tr")).toHaveAttribute("data-row-tone", "blocking");
    expect(screen.getByText("Session synchronization").closest("tr")).toHaveStyle({ backgroundColor: m3.errorContainer });
    expect(screen.queryByLabelText(/^Warnings/)).toBeNull();
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

  it("explains an unavailable command even with no check observations", () => {
    const { rerender } = render(<ReadinessCard {...props} checks={[]} status="UNKNOWN" stopCode="COMMAND_UNAVAILABLE" />);
    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(screen.getByText("Command not available on device (login environment)")).toBeInTheDocument();
    rerender(<ReadinessCard {...props} checks={[]} status="UNKNOWN" stopCode="COLLECTION_FAILED" />);
    expect(screen.queryByText("Command not available on device (login environment)")).toBeNull();
  });

  it("caps long messages to two lines and allows expansion", () => {
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(80);
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(32);
    try {
      render(<ReadinessCard {...props} checks={[{ ...checks[0], summary: "Cluster IP tables differ; " + "missing entry; ".repeat(20) }]} />);
      const message = screen.getByText(/^Cluster IP tables differ/);
      expect(message).toHaveStyle({ whiteSpace: "normal", WebkitLineClamp: "2" });
      const more = within(message.parentElement!).getByRole("button", { name: "More" });
      fireEvent.click(more);
      expect(more).toHaveAttribute("aria-expanded", "true");
      expect(message).toHaveStyle({ display: "block" });
      fireEvent.click(more);
      expect(message).toHaveStyle({ WebkitLineClamp: "2" });
    } finally { vi.restoreAllMocks(); }
  });

  it("shows the traffic and ARP information row with the numbers, the earlier-run ratios and a standby note, without blocking", () => {
    const title = "Traffic and ARP (information)";
    const traffic: ReadinessCheck[] = [
      { checkNo: 10, title, member: "Member 1", device_id: "opaque-1", result: "PASS", blocking: false,
        summary: "4 KB/s, 10 ARP entries; earlier run: 10 KB/s, 10 ARP entries; traffic 40% of the earlier run; ARP 100% of the earlier run; traffic below 50% of the earlier run" },
      { checkNo: 10, title, member: "Member 2", device_id: "opaque-2", result: "NOT_EVALUATED", blocking: false, summary: "Read on the active member only" },
    ];
    render(<ReadinessCard {...props} vendor="cisco_asa" status="READY" checks={[...checks.filter(check => check.checkNo === 1), ...traffic]} />);
    expect(screen.getByText("Ready")).toBeInTheDocument();
    expect(screen.getByLabelText("Blocking: 0")).toBeInTheDocument();
    expect(screen.getByLabelText("Passed: 1")).toBeInTheDocument();
    expect(screen.getByLabelText("Info: 1")).toBeInTheDocument();
    const cells = within(screen.getByText(title).closest("tr")!).getAllByRole("cell");
    expect(cells[0].textContent).toContain("4 KB/s, 10 ARP entries");
    expect(cells[0].textContent).toContain("traffic below 50% of the earlier run");
    expect(cells[1].textContent).toBe("Read on the active member only");
    expect(screen.getAllByText("info")).toHaveLength(1);
  });

  describe("single-address FortiGate (the primary reports both members)", () => {
    const one = [{ device_id: "opaque-1", hostname: "FW-TANGO-01", ha_role: null }];
    const row = (checkNo: number, result: ReadinessCheck["result"], blocking = true, summary = "As reported") =>
      ({ checkNo, title: `Check ${checkNo}`, member: "Member 1", device_id: "opaque-1", result, summary, blocking });
    const fgt = [row(1, "PASS"), row(7, "PASS"), row(10, "PASS", false, "1 MB/s, 40 ARP entries")];
    it("fills the second column from the primary, shows pass icons and the active/standby roles", () => {
      render(<ReadinessCard {...props} vendor="fortinet" members={one} checks={fgt} status="READY" />);
      const table = screen.getByRole("table");
      expect(within(table).getAllByRole("columnheader").map(cell => cell.textContent)).toEqual(
        ["Check", "FW-TANGO-01 · active (as the primary reports)", "Secondary · standby (as the primary reports)"]);
      const rows = within(table).getAllByRole("row");
      expect(within(rows[1]).getAllByRole("cell").map(cell => cell.textContent)).toEqual(["As reported", "As reported"]);
      expect(within(rows[3]).getAllByRole("cell").map(cell => cell.textContent)).toEqual(["1 MB/s, 40 ARP entries", "Read on the active member only"]);
      expect(screen.getAllByRole("img", { name: "Passed" })).toHaveLength(2);
      expect(screen.getAllByRole("img", { name: "Info" })).toHaveLength(1);
      expect(table.textContent).not.toContain("Not collected");
      expect(table.textContent).not.toContain("role unknown");
    });
    it("names no role when check 1 did not pass", () => {
      render(<ReadinessCard {...props} vendor="fortinet" members={one} checks={[row(1, "UNKNOWN"), row(7, "PASS")]} status="UNKNOWN" />);
      expect(within(screen.getByRole("table")).getAllByRole("columnheader")[2].textContent).toBe("Secondary · role unknown (as the primary reports)");
      expect(screen.getAllByRole("img", { name: "Unknown" })).toHaveLength(1);
    });
  });

  /** Live 2026-11-19: after a failover the inventory still said member 1 ACTIVE while the readiness said the reverse. */
  it("labels the member columns with the latest readiness cluster-state roles and their time, not the inventory roles", () => {
    const at = new Date(Date.now() - 300_000).toISOString();
    const observed = checks.map(check => check.checkNo !== 1 ? check : { ...check, observedAt: at,
      derived: check.device_id === "opaque-1" ? { role: "STANDBY", mode: "VSLS" } : JSON.stringify({ role: "ACTIVE", mode: "VSLS" }) });
    render(<ReadinessCard {...props} checks={observed} status="READY" />);
    const headers = within(screen.getByRole("table")).getAllByRole("columnheader");
    expect(headers.map(cell => cell.textContent)).toEqual(["Check", "FW-ROMEO-01-M2 · active (readiness, 5 min ago)",
      "FW-ROMEO-01-M1 · standby (readiness, 5 min ago)"]);
    expect(headers[1].querySelector("[data-role-source]")).toHaveAttribute("data-role-source", "readiness");
    // The rows follow the columns: member 2's check 1 summary is now in the first member column.
    const row = within(screen.getByRole("table")).getAllByRole("row")[1];
    expect(within(row).getAllByRole("cell").map(cell => cell.textContent)).toEqual(["Standby", "Active"]);
  });

  it("falls back to the inventory roles, labelled as such, when the readiness does not name every member", () => {
    const partial = checks.map(check => check.checkNo === 1 && check.device_id === "opaque-1" ? { ...check, derived: { role: "STANDBY" } } : check);
    render(<ReadinessCard {...props} checks={partial} status="READY" />);
    const headers = within(screen.getByRole("table")).getAllByRole("columnheader");
    expect(headers.map(cell => cell.textContent)).toEqual(["Check", "FW-ROMEO-01-M1 · active (inventory)", "FW-ROMEO-01-M2 · standby (inventory)"]);
    expect(headers[1].querySelector("[data-role-source]")).toHaveAttribute("data-role-source", "inventory");
  });
});
