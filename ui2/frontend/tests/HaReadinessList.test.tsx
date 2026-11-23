import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { CpFailoverSummary, DeviceSummary, FailoverIncident, ReadinessCheck } from "../src/auth/adminApi";
import { HaReadinessList, primaryReasonWord } from "../src/screens/HaReadinessList";
import { JobWindowContext } from "../src/shell/JobWindow";

const at = "2026-10-02T09:00:00Z";
const cluster = (title: string, vendor = "check_point") => ({ ref: title, title, members: [
  { device_id: `${title}-m1`, hostname: `FW-${title}-M1`, vendor_hint: vendor, ha_role: "active", inventory_collected_at: at },
  { device_id: `${title}-m2`, hostname: `FW-${title}-M2`, vendor_hint: vendor, ha_role: "standby", inventory_collected_at: at },
] as DeviceSummary[] });
const clusters = [cluster("CLS-ALPHA-01"), cluster("CLS-BRAVO-02", "palo_alto"), cluster("CLS-CHARLIE-03"), cluster("CLS-DELTA-04", "cisco_asa")];
type Status = "READY" | "NOT_READY" | "UNKNOWN";
const summary = (ref: string, status: Status | null, extra: Partial<CpFailoverSummary> = {}): CpFailoverSummary => ({
  clusterId: ref, unitId: ref, cluster_member_ref: ref, activeWindow: false, lastRunAt: null, lastRunState: null,
  lastRunOutcome: null, masked: false, canRunReadiness: true,
  readiness: status ? { status, observedAt: at, failedCheck: status === "NOT_READY" ? "Installed policy parity" : "", checks: [] } : null, ...extra,
});
const rows = { "CLS-ALPHA-01": [summary("CLS-ALPHA-01", "READY")], "CLS-BRAVO-02": [summary("CLS-BRAVO-02", "NOT_READY")] };
function setup(extra: Record<string, unknown> = {}) {
  const onOpen = vi.fn(), onRun = vi.fn(), onBulkRun = vi.fn(), onFailover = vi.fn();
  const view = render(<HaReadinessList clusters={clusters} rows={rows} running={null} busy={false} progress={{}} error={null}
    onOpen={onOpen} onRun={onRun} onBulkRun={onBulkRun} onFailover={onFailover} {...extra} />);
  return { onOpen, onRun, onBulkRun, onFailover, ...view };
}
const table = () => screen.getByRole("table", { name: "HA clusters" });
const row = (name: string) => within(table()).getByRole("row", { name });
const rowNames = () => within(table()).getAllByRole("row").slice(1).map(e => e.getAttribute("aria-label"));
const segment = (label: string, n: number) => screen.getByRole("button", { name: `${label}: ${n} clusters` });
const all = () => fireEvent.click(screen.getByRole("button", { name: /^All: \d+ clusters$/ }));
const check = (no: number, title: string, device: string, result: ReadinessCheck["result"], text: string, blocking = true, derived: unknown = {}) => ({
  checkNo: no, title, member: device.endsWith("m1") ? "Member 1" : "Member 2", device_id: device, result, status: result, summary: text, blocking, derived, observedAt: at });
afterEach(() => { cleanup(); vi.useRealTimers(); window.history.replaceState(null, "", "/"); });

it("opens on 'Can fail over now' when something can be failed over, and counts each cluster once per segment", () => {
  setup();
  expect(rowNames()).toEqual(["CLS-ALPHA-01"]);
  expect(segment("Can fail over now", 1)).toHaveAttribute("aria-pressed", "true");
  expect(segment("Blocked", 1)).toHaveAttribute("aria-pressed", "false");
  expect(segment("Unknown", 1)).toBeInTheDocument();
  expect(segment("Readiness only", 0)).toBeInTheDocument();
  expect(segment("All", 4)).toBeInTheDocument();
  expect(within(table()).getAllByRole("columnheader").map(h => h.textContent)).toEqual(["Status", "Cluster", "Roles", "Reason", "Checked", "Action"]);
});

it("opens on All when nothing can be failed over, and sorts All by state (blocked, unknown, can fail over, readiness only)", () => {
  const asa = { ...summary("CLS-DELTA-04", "READY"), vendor: "cisco_asa", readinessAvailable: true };
  setup({ rows: { ...rows, "CLS-ALPHA-01": [summary("CLS-ALPHA-01", "NOT_READY")], "CLS-DELTA-04": [asa] } });
  expect(segment("All", 4)).toHaveAttribute("aria-pressed", "true");
  expect(rowNames()).toEqual(["CLS-ALPHA-01", "CLS-BRAVO-02", "CLS-CHARLIE-03", "CLS-DELTA-04"]);
  cleanup();
  setup({ rows: { ...rows, "CLS-DELTA-04": [asa] } });
  all();
  expect(rowNames()).toEqual(["CLS-BRAVO-02", "CLS-CHARLIE-03", "CLS-ALPHA-01", "CLS-DELTA-04"]);
});

it("a segment filters its own clusters; search and vendor narrow it further", () => {
  setup();
  fireEvent.click(segment("Blocked", 1)); expect(rowNames()).toEqual(["CLS-BRAVO-02"]);
  fireEvent.click(segment("Unknown", 1)); expect(rowNames()).toEqual(["CLS-CHARLIE-03"]);
  all();
  fireEvent.change(screen.getByRole("textbox", { name: "Search cluster or member" }), { target: { value: " fw-cls-alpha-01-m2 " } });
  expect(rowNames()).toEqual(["CLS-ALPHA-01"]);
});

it("a READY row has a Fail over button that calls onFailover with the cluster and unit, and the row is not opened", () => {
  const { onFailover, onOpen } = setup();
  fireEvent.click(within(row("CLS-ALPHA-01")).getByRole("button", { name: "Fail over CLS-ALPHA-01" }));
  expect(onFailover).toHaveBeenCalledWith("CLS-ALPHA-01", "CLS-ALPHA-01");
  expect(onOpen).not.toHaveBeenCalled();
});

it("has no failover button for a masked (aiview) row", () => {
  setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...rows["CLS-ALPHA-01"][0], masked: true }] } });
  expect(within(row("CLS-ALPHA-01")).queryByRole("button", { name: /Fail over/ })).toBeNull();
});

it("a READY Cisco ASA pair says 'Readiness only' with an outline pill and no failover control", () => {
  const asa = { ...summary("CLS-DELTA-04", "READY"), vendor: "cisco_asa", readinessAvailable: true };
  setup({ rows: { ...rows, "CLS-DELTA-04": [asa] } });
  expect(rowNames()).toEqual(["CLS-ALPHA-01"]);
  expect(segment("Can fail over now", 1)).toBeInTheDocument();
  fireEvent.click(segment("Readiness only", 1));
  const asaRow = row("CLS-DELTA-04");
  expect(within(asaRow).getByText("Readiness only")).toBeInTheDocument();
  expect(asaRow.querySelector("[data-failover-execution-disabled]")).toHaveAttribute("title", expect.stringContaining("FAILOVER_VENDOR_NOT_AVAILABLE"));
  expect(asaRow.querySelector("[data-pill-outline='true']")).toHaveTextContent("Ready");
  expect(within(asaRow).queryByRole("button", { name: /Fail over/ })).toBeNull();
});

it("a NOT_READY chassis is blocked, is not 'Can fail over' for its READY virtual system, and has no chassis action", () => {
  const base = { ...summary("CLS-ALPHA-01", "NOT_READY"), unitKind: "VSX_CHASSIS" as const, chassisMode: "VSLS" as const, chassisAction: "FAILOVER_ALL_VIRTUAL_SYSTEMS" as const };
  const vs = { ...summary("CLS-ALPHA-01", "READY"), unitId: "opaque-vs-13", unitKind: "VIRTUAL_SYSTEM" as const, virtual_system: "VS-ALPHA-13" };
  setup({ rows: { ...rows, "CLS-ALPHA-01": [base, vs], "CLS-CHARLIE-03": [summary("CLS-CHARLIE-03", "READY")] } });
  expect(rowNames()).toEqual(["CLS-CHARLIE-03"]);
  fireEvent.click(segment("Blocked", 2));
  const chassis = row("CLS-ALPHA-01");
  expect(chassis).toHaveTextContent("1 virtual system: 1 ready, 0 not ready");
  expect(within(chassis).queryByRole("button", { name: /Fail over/ })).toBeNull();
  expect(within(chassis).getByRole("button", { name: "See blockers CLS-ALPHA-01" })).toBeInTheDocument();
  // The nested virtual-system row keeps its own state and action, under one chevron.
  fireEvent.click(within(chassis).getByRole("button", { name: "Virtual systems in CLS-ALPHA-01" }));
  expect(within(row("Virtual System VS-ALPHA-13")).getByRole("button", { name: "Fail over VS Virtual System VS-ALPHA-13" })).toBeInTheDocument();
});

it("a READY chassis offers the action its mode allows, with the virtual-system count", () => {
  const vs = (n: number) => ({ ...summary("CLS-ALPHA-01", "READY"), unitId: `opaque-vs-${n}`, unitKind: "VIRTUAL_SYSTEM" as const, virtual_system: `VS-ALPHA-${n}` });
  const base = { ...summary("CLS-ALPHA-01", "READY"), unitKind: "VSX_CHASSIS" as const };
  const { onFailover, unmount } = setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...base, chassisMode: "VSLS" as const, chassisAction: "FAILOVER_ALL_VIRTUAL_SYSTEMS" as const }, vs(1), vs(2)] } });
  fireEvent.click(within(row("CLS-ALPHA-01")).getByRole("button", { name: "Fail over all VS (2) CLS-ALPHA-01" }));
  expect(onFailover).toHaveBeenCalledWith("CLS-ALPHA-01", base.unitId);
  unmount();
  setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...base, chassisMode: "HA" as const, chassisAction: "FAILOVER" as const }, vs(1)] } });
  expect(within(row("CLS-ALPHA-01")).getByRole("button", { name: "Fail over chassis CLS-ALPHA-01" })).toBeInTheDocument();
  cleanup();
  setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...base, failoverRefusal: "VSX_CHASSIS_MODE_UNKNOWN" }, vs(1)] } });
  expect(segment("Can fail over now", 0)).toBeInTheDocument();
  fireEvent.click(segment("Blocked", 2));
  expect(row("CLS-ALPHA-01")).toHaveTextContent("VSX_CHASSIS_MODE_UNKNOWN");
  expect(within(row("CLS-ALPHA-01")).queryByRole("button", { name: /Fail over/ })).toBeNull();
});

it("a row click and Enter open the detail; there is no inline expansion, no selection and no 'Open full detail'", () => {
  const { onOpen } = setup();
  fireEvent.click(row("CLS-ALPHA-01"));
  expect(onOpen).toHaveBeenLastCalledWith("CLS-ALPHA-01", "CLS-ALPHA-01");
  fireEvent.keyDown(row("CLS-ALPHA-01"), { key: "Enter" });
  expect(onOpen).toHaveBeenCalledTimes(2);
  expect(screen.queryByRole("button", { name: "Open full detail" })).toBeNull();
  expect(screen.queryByRole("checkbox")).toBeNull();
  expect(screen.queryByRole("region", { name: /Checks for/ })).toBeNull();
  expect(screen.queryByText(/Readiness records/)).toBeNull();
  expect(document.querySelector("[data-readiness-rule]")).toBeNull();
});

it("a blocked row names the check, its headline and the member, and 'See blockers' opens the detail once", () => {
  const checks = [check(1, "Cluster state", "CLS-BRAVO-02-m1", "PASS", "Active", true, { role: "ACTIVE" }), check(1, "Cluster state", "CLS-BRAVO-02-m2", "PASS", "Standby", true, { role: "STANDBY" }),
    check(9, "State synchronization", "CLS-BRAVO-02-m1", "PASS", "Sync OK"), check(9, "State synchronization", "CLS-BRAVO-02-m2", "FAIL", "Sync status: Lost; +3 lost updates"),
    check(7, "Versions", "CLS-BRAVO-02-m1", "FAIL", "Differ"), check(7, "Versions", "CLS-BRAVO-02-m2", "FAIL", "Differ")];
  const blocked = { ...summary("CLS-BRAVO-02", "NOT_READY"), readiness: { status: "NOT_READY" as const, observedAt: at, failedCheck: "State synchronization", checks: checks as never } };
  const { onOpen } = setup({ rows: { ...rows, "CLS-BRAVO-02": [blocked] } });
  fireEvent.click(segment("Blocked", 1));
  const reason = row("CLS-BRAVO-02").querySelector("[data-reason-line]")!;
  expect(reason).toHaveTextContent("State synchronization – Sync status: Lost – on FW-CLS-BRAVO-02-M2 (standby)");
  expect(reason).toHaveTextContent("+1 more");
  expect(reason).not.toHaveTextContent("+3 lost updates");
  fireEvent.click(within(row("CLS-BRAVO-02")).getByRole("button", { name: "See blockers CLS-BRAVO-02" }));
  expect(onOpen).toHaveBeenCalledTimes(1);
});

it("keeps the chassis' virtual-system rows open through a refresh that changes statuses", () => {
  const vs = { ...summary("CLS-ALPHA-01", "READY"), unitId: "opaque-vs", unitKind: "VIRTUAL_SYSTEM" as const, virtual_system: "VS-ALPHA-07" };
  const base = { ...summary("CLS-ALPHA-01", "NOT_READY"), unitKind: "VSX_CHASSIS" as const, chassisAction: "FAILOVER" as const };
  const props = { clusters, running: null, busy: false, progress: {}, error: null, onOpen: vi.fn(), onRun: vi.fn(), onBulkRun: vi.fn() };
  const { rerender } = render(<HaReadinessList {...props} rows={{ ...rows, "CLS-ALPHA-01": [base, vs] }} />);
  all();
  fireEvent.click(screen.getByRole("button", { name: "Virtual systems in CLS-ALPHA-01" }));
  expect(row("Virtual System VS-ALPHA-07")).toBeInTheDocument();
  rerender(<HaReadinessList {...props} clusters={[...clusters].reverse()} rows={{ ...rows, "CLS-ALPHA-01": [{ ...base, readiness: { ...base.readiness!, status: "READY" } }, { ...vs, readiness: { ...vs.readiness!, status: "NOT_READY" } }] }} />);
  expect(screen.getByRole("button", { name: "Virtual systems in CLS-ALPHA-01" })).toHaveAttribute("aria-expanded", "true");
  expect(row("Virtual System VS-ALPHA-07")).toBeInTheDocument();
});

it("a pair-level check with identical texts says the member is not identified", () => {
  const text = "Config sync is disabled on 1 member(s): the running configurations are not kept equal";
  const checks = [check(4, "Configuration sync", "CLS-BRAVO-02-m1", "FAIL", text), check(4, "Configuration sync", "CLS-BRAVO-02-m2", "FAIL", text)];
  setup({ rows: { ...rows, "CLS-BRAVO-02": [{ ...summary("CLS-BRAVO-02", "NOT_READY"), readiness: { status: "NOT_READY" as const, observedAt: at, failedCheck: "Configuration sync", checks: checks as never } }] } });
  fireEvent.click(segment("Blocked", 1));
  expect(row("CLS-BRAVO-02").querySelector("[data-reason-where]")).toHaveAttribute("data-reason-where", "unidentified");
  expect(row("CLS-BRAVO-02")).toHaveTextContent("member not identified by this check");
});

it("an unknown cluster with no unit row says 'Never checked'", () => {
  setup();
  fireEvent.click(segment("Unknown", 1));
  const unknown = row("CLS-CHARLIE-03");
  expect(unknown.querySelector("[data-reason-line]")).toHaveTextContent("Never checked");
  expect(within(unknown).getByText("UNKNOWN")).toBeInTheDocument();
  // No unit row came back for this cluster: there is nothing the server could run, so no button is drawn.
  expect(within(unknown).queryByRole("button", { name: /Run pre-checks/ })).toBeNull();
});

it("Run pre-checks on an unknown unit calls onRun", () => {
  const unknown = summary("CLS-CHARLIE-03", null);
  const { onRun } = setup({ rows: { ...rows, "CLS-CHARLIE-03": [unknown] } });
  fireEvent.click(segment("Unknown", 1));
  fireEvent.click(within(row("CLS-CHARLIE-03")).getByRole("button", { name: "Run pre-checks CLS-CHARLIE-03" }));
  expect(onRun).toHaveBeenCalledWith(unknown);
});

it("a failed summary read makes every row unknown, says so, and offers no failover", () => {
  setup({ summaryFailed: true });
  expect(segment("Can fail over now", 0)).toBeInTheDocument();
  expect(segment("Unknown", 4)).toBeInTheDocument();
  all();
  expect(screen.queryByRole("button", { name: /^Fail over/ })).toBeNull();
  expect(within(table()).getAllByText("Readiness could not be read").length).toBe(4);
});

it("unreadable incidents show a would-be Ready row as Unknown with the reason, and offer no failover", () => {
  setup({ incidentsFailed: true });
  expect(segment("Can fail over now", 0)).toBeInTheDocument();
  all();
  expect(screen.queryByRole("button", { name: /^Fail over/ })).toBeNull();
  const ready = row("CLS-ALPHA-01");
  expect(ready).toHaveAttribute("data-row-state", "UNKNOWN");
  expect(within(ready).queryByText("Ready")).toBeNull();
  expect(within(ready).getByText(/incidents could not be read/)).toBeInTheDocument();
  expect(within(ready).queryByRole("button", { name: /Run pre-checks/ })).toBeNull();
  // A row that is not ready keeps its own verdict.
  expect(row("CLS-BRAVO-02")).toHaveAttribute("data-row-state", "BLOCKED");
});

it("an open incident marks the cluster's row and its action opens the incident", () => {
  const incident: FailoverIncident = { incidentRef: "inc-1", clusterId: "CLS-ALPHA-01", unitId: "CLS-ALPHA-01", cluster_member_ref: "CLS-ALPHA-01", reason: "r", openedAt: at,
    incidentActive: true, blocksEstate: false, unresolvedSends: 0, members: [] };
  const { onOpen } = setup({ incidents: [incident] });
  fireEvent.click(segment("Blocked", 2));
  expect(within(row("CLS-ALPHA-01")).getByText("Incident open")).toBeInTheDocument();
  fireEvent.click(within(row("CLS-ALPHA-01")).getByRole("button", { name: "Open incident CLS-ALPHA-01" }));
  expect(onOpen).toHaveBeenCalledTimes(1);
});

it("opens on the segment named by ?readiness= (the Overview's needs-attention line)", () => {
  window.history.replaceState(null, "", "/?screen=operations&readiness=NOT_READY");
  setup();
  expect(segment("Blocked", 1)).toHaveAttribute("aria-pressed", "true");
  expect(rowNames()).toEqual(["CLS-BRAVO-02"]);
});

it("a stale READY stays in 'Can fail over now' with a Stale chip and a notice", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-17T12:00:00Z"));
  setup();
  expect(rowNames()).toEqual(["CLS-ALPHA-01"]);
  expect(row("CLS-ALPHA-01").querySelector("[data-stale-evaluation]")).toBeInTheDocument();
  expect(screen.getByRole("status")).toHaveTextContent("a failover repeats every pre-check");
});

it("'Re-check shown (n)' re-checks the visible rows and asks once before several", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-17T12:00:00Z"));
  const { onBulkRun } = setup();
  all();
  expect(screen.getByTestId("toolbar-run-readiness")).toHaveTextContent("Re-check shown (2)");
  fireEvent.click(screen.getByTestId("toolbar-run-readiness"));
  expect(onBulkRun).not.toHaveBeenCalled();
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Re-check" }));
  expect(onBulkRun).toHaveBeenCalledTimes(1);
  expect(onBulkRun.mock.calls[0][0].map((r: CpFailoverSummary) => r.unitId).sort()).toEqual(["CLS-ALPHA-01", "CLS-BRAVO-02"]);
});

it("locks the Re-check button with the role reason when the server grants nobody a run", () => {
  const refused = Object.fromEntries(Object.entries(rows).map(([ref, units]) => [ref, units.map(unit => ({ ...unit, canRunReadiness: false }))]));
  setup({ rows: refused });
  expect(screen.getByTestId("toolbar-run-readiness")).toBeDisabled();
  expect(screen.getByTestId("toolbar-run-readiness")).toHaveTextContent("Re-check shown · Role: not permitted");
});

it("shows no per-row re-check outside the job window; the header button carries the window, an administrator keeps both", () => {
  const closed = render(<JobWindowContext.Provider value={{ open: false, label: "Next window: 18:00" }}>
    <HaReadinessList clusters={clusters} rows={rows} running={null} busy={false} progress={{}} error={null} onOpen={vi.fn()} onRun={vi.fn()} onBulkRun={vi.fn()} />
  </JobWindowContext.Provider>);
  expect(screen.queryByRole("button", { name: /^Re-check CLS/ })).toBeNull();
  expect(screen.getByTestId("toolbar-run-readiness")).toHaveTextContent("Re-check shown (1) · Next window: 18:00");
  expect(screen.getByTestId("toolbar-run-readiness")).toBeDisabled();
  closed.unmount();
  render(<JobWindowContext.Provider value={{ open: false, label: "Next window: 18:00", adminBypass: true }}>
    <HaReadinessList clusters={clusters} rows={rows} running={null} busy={false} progress={{}} error={null} onOpen={vi.fn()} onRun={vi.fn()} onBulkRun={vi.fn()} />
  </JobWindowContext.Provider>);
  expect(screen.getByRole("button", { name: "Re-check CLS-ALPHA-01" })).toBeEnabled();
  expect(screen.getByTestId("toolbar-run-readiness")).toBeEnabled();
});

it("shows per-row progress and disables the row while it runs", () => {
  setup({ running: "CLS-ALPHA-01", busy: true, progress: { "CLS-ALPHA-01": "Running" } });
  expect(within(row("CLS-ALPHA-01")).getAllByRole("status").map(s => s.textContent).join(" ")).toMatch(/checking[\s\S]*Running|Running/);
  expect(within(row("CLS-ALPHA-01")).getByRole("button", { name: "Fail over CLS-ALPHA-01" })).toBeDisabled();
});

it("a declined business-hours question is a neutral notice on its own row; any other failure is an error there", () => {
  setup({ error: { unitId: "CLS-ALPHA-01", cancelled: true, text: "Not started: business hours were not confirmed." } });
  const notice = row("CLS-ALPHA-01").querySelector("[data-run-notice]")!;
  expect(notice).toHaveAttribute("data-run-notice", "neutral");
  expect(notice).toHaveAttribute("role", "status");
  expect(notice).toHaveTextContent("Pre-checks not started. Not started: business hours were not confirmed.");
  expect(screen.queryByRole("alert")).toBeNull();
  cleanup();
  setup({ error: { unitId: "CLS-ALPHA-01", cancelled: false, text: "Could not start or refresh the readiness run." } });
  expect(row("CLS-ALPHA-01").querySelector("[data-run-notice]")).toHaveAttribute("role", "alert");
});

it("collapses unsupported vendors under All, with no run action", () => {
  setup({ clusters: [...clusters.slice(0, 2), cluster("CLS-ECHO-05", "juniper")] });
  all();
  const group = screen.getByText("Not supported · 1 clusters").closest("details")!;
  expect(group).not.toHaveAttribute("open");
  expect(within(group).queryByRole("button", { name: /Run pre-checks/ })).toBeNull();
  expect(within(group).getByText("CLS-ECHO-05")).toBeInTheDocument();
});

it("lists a Cisco ASA pair without readiness as listed only", () => {
  const asa: CpFailoverSummary = { ...summary("CLS-DELTA-04", "READY"), readiness: null, canRunReadiness: false, readinessAvailable: false };
  setup({ rows: { ...rows, "CLS-DELTA-04": [asa] } });
  all();
  const item = screen.getByText("CLS-DELTA-04").closest("li")!;
  expect(within(item).getByText("Readiness not available yet")).toBeInTheDocument();
  expect(within(item).queryByRole("button")).toBeNull();
});

it("evaluates a FortiGate pair as readiness only, with the primary's roles", () => {
  const fgt = { ...summary("CLS-DELTA-04", "READY"), vendor: "fortinet", readinessAvailable: true,
    readiness: { status: "READY" as const, observedAt: at, failedCheck: "", checks: [check(1, "Cluster state", "CLS-DELTA-04-m1", "PASS", "Primary", true, { role: "PRIMARY" })] as never } };
  setup({ clusters: [...clusters.slice(0, 3), cluster("CLS-DELTA-04", "fortinet")], rows: { ...rows, "CLS-DELTA-04": [fgt] } });
  fireEvent.click(segment("Readiness only", 1));
  expect(within(row("CLS-DELTA-04")).getByText("Readiness only")).toBeInTheDocument();
  expect(within(row("CLS-DELTA-04")).queryByRole("button", { name: /Fail over/ })).toBeNull();
});

it("shows roles from the readiness read when it names every member, else the inventory roles, labelled as such", () => {
  const checks = [check(1, "Cluster state", "CLS-ALPHA-01-m1", "PASS", "Standby", true, { role: "STANDBY" }), check(1, "Cluster state", "CLS-ALPHA-01-m2", "PASS", "Active", true, { role: "ACTIVE" })];
  setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...summary("CLS-ALPHA-01", "READY"), readiness: { status: "READY" as const, observedAt: at, failedCheck: "", checks: checks as never } }] } });
  expect(row("CLS-ALPHA-01")).toHaveTextContent("Active: FW-CLS-ALPHA-01-M2 → Standby: FW-CLS-ALPHA-01-M1");
  expect(row("CLS-ALPHA-01").querySelector("[data-role-source='readiness']")).toBeInTheDocument();
  cleanup();
  setup();
  expect(row("CLS-ALPHA-01")).toHaveTextContent("Active: FW-CLS-ALPHA-01-M1 → Standby: FW-CLS-ALPHA-01-M2");
  expect(row("CLS-ALPHA-01").querySelector("[data-role-source='inventory']")).toBeInTheDocument();
});

// Post-release review 2026-10-20 (L1): the shared primary reason (the Overview and the Estate view use it) goes through the label map.
it("words a bare READY reason as Ready and keeps check names and sentences as they came", () => {
  const ready = summary("CLS-ALPHA-01", "READY");
  expect(primaryReasonWord({ ...ready, refusalReason: "CHECK_NOT_READY", readiness: { ...ready.readiness!, status: "NOT_READY", checks: [
    { checkNo: 4, title: "Configuration sync", member: "FW-TANGO-04", result: "FAIL", summary: "Config sync is disabled on 1 member(s): the running configurations are not kept equal", blocking: true, status: "FAIL", derived: {} }] } }))
    .toBe("Configuration sync: Config sync is disabled on 1 member(s): the running configurations are not kept equal");
  expect(primaryReasonWord({ ...ready, refusalReason: "READY" })).toBe("Ready");
  expect(primaryReasonWord({ ...ready, refusalReason: "NOT_READY" })).toBe("Not ready");
  expect(primaryReasonWord(ready)).toBe("No blocking conditions observed");
  expect(primaryReasonWord(summary("CLS-BRAVO-02", "NOT_READY"))).toBe("Installed policy parity");
});
