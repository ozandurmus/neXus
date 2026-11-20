import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { CpFailoverSummary, DeviceSummary } from "../src/auth/adminApi";
import { HaReadinessList, primaryReasonWord } from "../src/screens/HaReadinessList";
import { JobWindowContext } from "../src/shell/JobWindow";
const at = "2026-10-02T09:00:00Z";
const cluster = (title: string, vendor = "check_point") => ({ ref: title, title, members: [
  { device_id: `${title}-m1`, hostname: `FW-${title}-M1`, vendor_hint: vendor, ha_role: "active", inventory_collected_at: at },
  { device_id: `${title}-m2`, hostname: `FW-${title}-M2`, vendor_hint: vendor, ha_role: "standby", inventory_collected_at: at },
] as DeviceSummary[] });
const clusters = [cluster("CLS-ALPHA-01"), cluster("CLS-BRAVO-02", "palo_alto"), cluster("CLS-CHARLIE-03"), cluster("CLS-DELTA-04", "cisco_asa")];
const summary = (ref: string, status: "READY" | "NOT_READY"): CpFailoverSummary => ({
  clusterId: ref, unitId: ref, cluster_member_ref: ref, activeWindow: false, lastRunAt: null, lastRunState: null,
  lastRunOutcome: null, masked: false, canRunReadiness: true,
  readiness: { status, observedAt: at, failedCheck: status === "NOT_READY" ? "Installed policy parity" : "", checks: [] },
});
const rows = { "CLS-ALPHA-01": [summary("CLS-ALPHA-01", "READY")], "CLS-BRAVO-02": [summary("CLS-BRAVO-02", "NOT_READY")] };
function setup(extra = {}) {
  const onOpen = vi.fn(), onRun = vi.fn(), onBulkRun = vi.fn();
  const view = render(<HaReadinessList clusters={clusters} rows={rows} running={null} busy={false} progress={{}} error={null} onOpen={onOpen} onRun={onRun} onBulkRun={onBulkRun} {...extra} />);
  return { onOpen, onRun, onBulkRun, ...view };
}
const table = () => screen.getByRole("table", { name: "HA clusters" });
const row = (name: string) => within(table()).getByRole("row", { name });
afterEach(() => vi.useRealTimers());
it("keeps cluster and VS expansion through summary arrival, refresh and reordering", () => {
  const vs = { ...summary(clusters[0].ref, "NOT_READY"), unitId: "opaque-vs", virtual_system: "VS-ALPHA-07" };
  const props = { clusters, rows: {}, running: null, busy: false, progress: {}, error: null,
    onOpen: vi.fn(), onRun: vi.fn(), onBulkRun: vi.fn() };
  const { rerender } = render(<HaReadinessList {...props} />);
  const expand = (title: string) => within(row(title)).getByRole("button", { name: `Checks for ${title}` });
  fireEvent.click(expand(clusters[0].title));
  expect(row(clusters[0].title)).toHaveAttribute("aria-expanded", "true");
  const refreshed: Record<string, CpFailoverSummary[]> = { ...rows, [clusters[0].ref]: [{ ...summary(clusters[0].ref, "NOT_READY"), clusterId: "opaque-cluster", unitId: "opaque-cluster" }, vs] };
  rerender(<HaReadinessList {...props} rows={refreshed} />);
  expect(row(clusters[0].title)).toHaveAttribute("aria-expanded", "true");
  expect(expand(clusters[0].title)).toHaveAttribute("aria-expanded", "true");
  expect(screen.getByRole("region", { name: `Checks for ${clusters[0].title}` })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: `Virtual systems in ${clusters[0].title}` }));
  fireEvent.click(expand("Virtual System VS-ALPHA-07"));
  const updated = { ...refreshed, [clusters[0].ref]: refreshed[clusters[0].ref].map(unit => ({ ...unit, readiness: { ...unit.readiness!, status: "READY" as const } })) };
  rerender(<HaReadinessList {...props} clusters={[...clusters].reverse()} rows={updated} />);
  expect(row(clusters[0].title)).toHaveAttribute("aria-expanded", "true");
  expect(row("Virtual System VS-ALPHA-07")).toHaveAttribute("aria-expanded", "true");
  fireEvent.click(expand(clusters[0].title));
  expect(row(clusters[0].title)).toHaveAttribute("aria-expanded", "false");
  expect(row("Virtual System VS-ALPHA-07")).toHaveAttribute("aria-expanded", "true");
  rerender(<HaReadinessList {...props} rows={{}} />);
  expect(row(clusters[0].title)).toHaveAttribute("aria-expanded", "false");
});
it("uses full width, compact rows and cluster counts, sorting not ready, unknown, ready", () => {
  setup();
  const layout = table().closest(".ha-readiness-full-width")!;
  expect(layout).toHaveStyle({ width: "100%", minWidth: "0" });
  expect(getComputedStyle(layout).maxWidth).toBe("");
  for (const name of ["Ready", "Not ready", "UNKNOWN", "Not supported"]) expect(screen.getByRole("button", { name: `${name}: 1 clusters` })).toHaveAttribute("aria-pressed", "false");
  expect(within(table()).getAllByRole("row").slice(1).map(e => e.getAttribute("aria-label"))).toEqual(["CLS-BRAVO-02", "CLS-CHARLIE-03", "CLS-ALPHA-01"]);
  expect(row("CLS-ALPHA-01")).toHaveStyle({ height: "48px" });
  expect(within(table()).getAllByRole("columnheader")).toHaveLength(7);
  expect(screen.queryByText("NOT_READY")).toBeNull();
});
it("keeps the complete status label visible", () => {
  setup();
  const label = within(row("CLS-BRAVO-02")).getByText("Not ready");
  expect(label).toHaveTextContent("Not ready");
  expect(within(table()).getByRole("columnheader", { name: "Status" })).toHaveStyle({ width: "140px" });
  expect(label.parentElement).toHaveStyle({ minWidth: "min-content" });
});
it("combines summary filters with cluster/member search and vendor filter", () => {
  setup();
  for (const [label, name] of [["Ready", "CLS-ALPHA-01"], ["Not ready", "CLS-BRAVO-02"], ["UNKNOWN", "CLS-CHARLIE-03"]]) {
    const tile = screen.getByRole("button", { name: `${label}: 1 clusters` });
    fireEvent.click(tile);
    expect(tile).toHaveAttribute("aria-pressed", "true");
    expect(within(table()).getAllByRole("row")).toHaveLength(2);
    expect(row(name)).toBeInTheDocument();
    fireEvent.click(tile);
  }
  fireEvent.change(screen.getByRole("textbox", { name: "Search cluster or member" }), { target: { value: " fw-cls-alpha-01-m2 " } });
  expect(row("CLS-ALPHA-01")).toBeInTheDocument();
  expect(screen.queryByRole("row", { name: "CLS-BRAVO-02" })).toBeNull();
  fireEvent.mouseDown(screen.getByRole("combobox", { name: "Vendor" }));
  fireEvent.click(screen.getByRole("option", { name: "Palo Alto" }));
  expect(screen.getByText("No clusters match these filters")).toBeInTheDocument();
});
it("collapses unsupported vendors with no run actions and opens them via their filter", () => {
  setup();
  const group = screen.getByText("Not supported · 1 clusters").closest("details")!;
  expect(group).not.toHaveAttribute("open");
  expect(within(group).queryByRole("button", { name: "Run pre-checks" })).toBeNull();
  expect(within(group).getByText(/Readiness pre-checks are not supported/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Not supported: 1 clusters" }));
  expect(group).toHaveAttribute("open");
  expect(within(group).getByText("CLS-DELTA-04")).toBeInTheDocument();
});
it("keeps an unobserved vendor unknown", () => {
  setup({ clusters: [cluster("CLS-ECHO-05", "")] });
  expect(screen.getByRole("button", { name: "UNKNOWN: 1 clusters" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Not supported: 0 clusters" })).toBeInTheDocument();
});
it("collapses nested VS rows, selects only visible units, and opens independent inline details", () => {
  const vs = { ...summary("CLS-ALPHA-01", "NOT_READY"), unitId: "opaque-vs", virtual_system: "VS-ALPHA-07", members: clusters[0].members.map((member, index) => ({ ...member, ha_role: index ? "active" : "standby" })) };
  const { onOpen, onRun, onBulkRun } = setup({ rows: { ...rows, "CLS-ALPHA-01": [...rows["CLS-ALPHA-01"], vs] } });
  expect(screen.queryByRole("row", { name: "Virtual System VS-ALPHA-07" })).toBeNull();
  fireEvent.click(screen.getByRole("checkbox", { name: "Select all visible" }));
  expect(screen.getByRole("button", { name: "Run pre-checks (2)" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Virtual systems in CLS-ALPHA-01" }));
  const vsRow = row("Virtual System VS-ALPHA-07");
  expect(within(vsRow).getByText(/Active: FW-CLS-ALPHA-01-M2/)).toHaveTextContent("Standby: FW-CLS-ALPHA-01-M1");
  fireEvent.click(within(vsRow).getByRole("button", { name: "Run pre-checks" }));
  expect(onRun).toHaveBeenCalledWith(vs);
  expect(vsRow).toHaveAttribute("aria-expanded", "false");
  fireEvent.click(screen.getByRole("checkbox", { name: "Select all visible" }));
  fireEvent.click(screen.getByRole("button", { name: "Run pre-checks (3)" }));
  expect(onBulkRun).toHaveBeenCalledWith([rows["CLS-ALPHA-01"][0], vs, rows["CLS-BRAVO-02"][0]]);
  fireEvent.click(vsRow);
  expect(onOpen).not.toHaveBeenCalled();
  fireEvent.click(within(screen.getByRole("region", { name: "Checks for Virtual System VS-ALPHA-07" })).getByRole("button", { name: "Open full detail" }));
  expect(onOpen).toHaveBeenCalledWith("CLS-ALPHA-01", "opaque-vs");
  expect(screen.getByRole("button", { name: "Ready: 1 clusters" })).toBeInTheDocument();
});
it("shows relative time and hover-only absolute and roles-observed times", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-02T09:35:00Z")); setup();
  const item = row("CLS-BRAVO-02");
  expect(within(item).getByText("35 min ago")).toHaveAttribute("datetime", at);
  expect(within(item).getByText("35 min ago")).toHaveAttribute("title", expect.stringContaining(at));
  expect(item.querySelector('[title^="Roles observed:"]')).toHaveAttribute("title", "Roles observed: 2026-10-02 12:00 GMT+3");
  act(() => vi.advanceTimersByTime(60_000));
  expect(within(item).getByText("36 min ago")).toBeInTheDocument();
});
it("supports Enter to expand and Space to select, without expanding on checkbox click", () => {
  const { onOpen } = setup();
  const item = row("CLS-ALPHA-01");
  fireEvent.keyDown(item, { key: "Enter" });
  expect(item).toHaveAttribute("aria-expanded", "true");
  expect(screen.getByRole("region", { name: "Checks for CLS-ALPHA-01" })).toBeInTheDocument();
  expect(onOpen).not.toHaveBeenCalled();
  fireEvent.keyDown(item, { key: "Enter" });
  fireEvent.keyDown(item, { key: " " });
  expect(screen.getByRole("checkbox", { name: "Select CLS-ALPHA-01" })).toBeChecked();
  expect(screen.getByRole("toolbar", { name: "Bulk readiness actions" })).toHaveStyle({ position: "sticky" });
  fireEvent.click(screen.getByRole("checkbox", { name: "Select CLS-BRAVO-02" }));
  expect(row("CLS-BRAVO-02")).toHaveAttribute("aria-expanded", "false");
  fireEvent.click(screen.getByRole("button", { name: "Clear selection" }));
  expect(screen.queryByRole("toolbar", { name: "Bulk readiness actions" })).toBeNull();
});
it("selects and deselects only filtered visible authorized rows, retaining other selection", () => {
  setup();
  const selectAll = screen.getByRole("checkbox", { name: "Select all visible" });
  fireEvent.click(screen.getByRole("checkbox", { name: "Select CLS-ALPHA-01" }));
  expect(selectAll).toHaveAttribute("data-indeterminate", "true");
  fireEvent.click(screen.getByRole("button", { name: "Not ready: 1 clusters" }));
  fireEvent.click(selectAll);
  expect(selectAll).toBeChecked();
  expect(screen.getByRole("button", { name: "Run pre-checks (2)" })).toBeInTheDocument();
  fireEvent.click(selectAll);
  expect(screen.getByRole("button", { name: "Run pre-checks (1)" })).toBeInTheDocument();
});
it("offers run actions to aiview when the server grants them, and hides them when it refuses", () => {
  const { rerender } = setup();
  fireEvent.click(screen.getByRole("checkbox", { name: "Select all visible" }));
  const aiviewRows = Object.fromEntries(Object.entries(rows).map(([ref, units]) => [ref, units.map(unit => ({ ...unit, masked: true, canRunReadiness: true }))]));
  rerender(<HaReadinessList clusters={clusters} rows={aiviewRows} running={null} busy={false} progress={{}} error={null} onOpen={vi.fn()} onRun={vi.fn()} onBulkRun={vi.fn()} />);
  expect(screen.getAllByRole("button", { name: "Run pre-checks" }).length).toBeGreaterThan(0);
  expect(screen.getByRole("checkbox", { name: "Select all visible" })).toBeEnabled();
  const refused = Object.fromEntries(Object.entries(rows).map(([ref, units]) => [ref, units.map(unit => ({ ...unit, masked: true, canRunReadiness: false }))]));
  rerender(<HaReadinessList clusters={clusters} rows={refused} running={null} busy={false} progress={{}} error={null} onOpen={vi.fn()} onRun={vi.fn()} onBulkRun={vi.fn()} />);
  expect(screen.queryByRole("toolbar", { name: "Bulk readiness actions" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Run pre-checks" })).toBeNull();
  expect(within(table()).queryByRole("columnheader", { name: "Actions" })).toBeNull();
  expect(within(table()).getAllByRole("columnheader")).toHaveLength(6);
  expect(screen.getByRole("checkbox", { name: "Select all visible" })).toBeDisabled();
  fireEvent.keyDown(row("CLS-ALPHA-01"), { key: " " });
  expect(screen.queryByRole("toolbar", { name: "Bulk readiness actions" })).toBeNull();
});

it("shows both observed members in inline details and wraps long messages", () => {
  const cluster = clusters[0];
  const checks = cluster.members.map((member, index) => ({ checkNo: 2, title: "Cluster IP table", device_id: member.device_id,
    member: `Member ${index + 1}`, result: "FAIL" as const, status: "FAIL" as const, blocking: true, derived: {}, summary: "Tables differ; " + "missing entry on observer; ".repeat(30) }));
  setup({ rows: { [cluster.ref]: [{ ...summary(cluster.ref, "NOT_READY"), masked: true, members: [...cluster.members].reverse(), readiness: { status: "NOT_READY", observedAt: at, failedCheck: "Cluster IP table", checks } }] } });
  fireEvent.click(row(cluster.title));
  const detail = screen.getByRole("region", { name: `Checks for ${cluster.title}` });
  expect(within(detail).getAllByRole("columnheader").map(cell => cell.textContent)).toEqual(["Check", "FW-CLS-ALPHA-01-M1 · active (inventory)", "FW-CLS-ALPHA-01-M2 · standby (inventory)"]);
  expect(detail.querySelectorAll(".readiness-message")).toHaveLength(2);
  for (const message of detail.querySelectorAll(".readiness-message")) {
    expect(message.parentElement?.parentElement).toHaveStyle({ whiteSpace: "normal", overflowWrap: "anywhere" });
    expect(message).toHaveStyle({ WebkitLineClamp: "2" });
  }
  expect(table()).not.toHaveStyle({ minWidth: "1100px" });
  expect(within(table()).getByRole("columnheader", { name: "Last evaluated" })).toHaveStyle({ width: "145px" });
});
it("shows per-row progress and disables actions during a batch", () => {
  setup({ running: "CLS-ALPHA-01", busy: true, progress: { "CLS-ALPHA-01": "Running", "CLS-BRAVO-02": "Queued" } });
  const button = within(row("CLS-ALPHA-01")).getByRole("button", { name: "Run pre-checks" });
  expect(button).toBeDisabled(); expect(button).toHaveAttribute("aria-busy", "true");
  expect(within(button).getByRole("progressbar")).toBeInTheDocument();
  expect(within(row("CLS-ALPHA-01")).getByRole("status")).toHaveTextContent("Running");
  expect(within(row("CLS-BRAVO-02")).getByRole("status")).toHaveTextContent("Queued");
  expect(screen.getByRole("checkbox", { name: "Select all visible" })).toBeDisabled();
});

it("shows the stored login-environment stop reason for unknown base and virtual units", () => {
  const base = { ...summary(clusters[0].ref, "READY"), readiness: {
    status: "UNKNOWN" as const, observedAt: at, failedCheck: "", stopCode: "COMMAND_UNAVAILABLE", checks: [],
  } };
  const vs = { ...base, unitId: "opaque-vs", virtual_system: "VS-ALPHA-07" };
  setup({ rows: { [clusters[0].ref]: [base, vs] } });
  expect(within(row(clusters[0].title)).getByText("Command not available on device (login environment)")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: `Virtual systems in ${clusters[0].title}` }));
  expect(within(row("Virtual System VS-ALPHA-07")).getByText("Command not available on device (login environment)")).toBeInTheDocument();
});

it.each([["UNSUPPORTED_MODE", "Cluster mode not supported"], ["IDENTITY_NOT_RECORDED", "Device identity not recorded"], ["OPEN_INCIDENT", "Open failover incident"]])("shows the failover refusal %s in words, the code on hover, with mode", (refusalReason, words) => {
  setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...rows["CLS-ALPHA-01"][0], refusalReason, mode: "HA" }] } });
  expect(within(row("CLS-ALPHA-01")).getByText(words)).toHaveAttribute("data-raw", refusalReason);
  expect(within(row("CLS-ALPHA-01")).getByText("Mode: High availability")).toBeInTheDocument();
});
it("writes the Unknown-versus-Not-ready rule on screen and drops a mode line that says nothing", () => {
  setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...rows["CLS-ALPHA-01"][0], mode: "UNKNOWN" }] } });
  expect(document.querySelector("[data-readiness-rule]")).toHaveTextContent(/Not ready: a pre-check found a blocking condition · UNKNOWN: readiness could not be established/);
  expect(within(row("CLS-ALPHA-01")).queryByText(/^Mode:/)).toBeNull();
});
it("puts Run readiness check on the toolbar and the staleness notice, asking once before a multi-cluster run", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-17T12:00:00Z"));
  const stale = new Date("2026-10-09T12:00:00Z").toISOString();
  const staleRows = { "CLS-ALPHA-01": [{ ...summary("CLS-ALPHA-01", "READY"), readiness: { status: "READY" as const, observedAt: stale, failedCheck: "", checks: [] } }],
    "CLS-BRAVO-02": [{ ...summary("CLS-BRAVO-02", "NOT_READY"), readiness: { status: "NOT_READY" as const, observedAt: stale, failedCheck: "", checks: [] } }] };
  const { onBulkRun } = setup({ rows: staleRows });
  expect(screen.getByTestId("toolbar-run-readiness")).toHaveTextContent("Run readiness check (2 clusters)");
  expect(within(screen.getByRole("status")).getByTestId("stale-run-readiness")).toBeInTheDocument();
  fireEvent.click(screen.getByTestId("stale-run-readiness"));
  expect(onBulkRun).not.toHaveBeenCalled();
  expect(screen.getByRole("dialog", { name: "Run readiness check on 2 clusters?" })).toBeInTheDocument();
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Run readiness check" }));
  expect(onBulkRun).toHaveBeenCalledWith([staleRows["CLS-BRAVO-02"][0], staleRows["CLS-ALPHA-01"][0]]);
});
it("locks the toolbar button with the role reason when the server grants nobody a run", () => {
  const refused = Object.fromEntries(Object.entries(rows).map(([ref, units]) => [ref, units.map(unit => ({ ...unit, canRunReadiness: false }))]));
  setup({ rows: refused });
  expect(screen.getByTestId("toolbar-run-readiness")).toBeDisabled();
  expect(screen.getByTestId("toolbar-run-readiness")).toHaveTextContent("Run readiness check · Role: not permitted");
});


const NOW = new Date("2026-10-17T12:00:00Z");
const hoursAgo = (h: number) => new Date(NOW.getTime() - h * 3600_000).toISOString();
const evaluated = (ref: string, status: "READY" | "NOT_READY", observedAt: string): CpFailoverSummary => ({
  ...summary(ref, status), readiness: { status, observedAt, failedCheck: "", checks: [] },
});
const withNow = () => { vi.useFakeTimers(); vi.setSystemTime(NOW); };

it("flags a stale evaluation per row and warns above the list, without changing Ready", () => {
  withNow();
  const stale = hoursAgo(8 * 24);
  setup({ rows: { "CLS-ALPHA-01": [evaluated("CLS-ALPHA-01", "READY", stale)], "CLS-BRAVO-02": [evaluated("CLS-BRAVO-02", "NOT_READY", stale)] } });
  expect(screen.getByText("Readiness was last evaluated 8 d ago; run a readiness check before relying on Ready.")).toBeInTheDocument();
  // The e2e safety fixture reads any non-info/success Alert as a loading error; the notice is a status, not an alert.
  expect(screen.queryByRole("alert")).toBeNull();
  expect(screen.getByRole("status")).toHaveTextContent("run a readiness check before relying on Ready");
  expect(within(row("CLS-ALPHA-01")).getByText("Stale")).toBeInTheDocument();
  expect(within(row("CLS-ALPHA-01")).getByText("8 d ago")).toBeInTheDocument();
  expect(within(row("CLS-ALPHA-01")).getByText("Ready")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Ready: 1 clusters" })).toBeInTheDocument();
});

it("shows no staleness signal when evaluations are recent", () => {
  withNow();
  setup({ rows: { "CLS-ALPHA-01": [evaluated("CLS-ALPHA-01", "READY", hoursAgo(23))], "CLS-BRAVO-02": [evaluated("CLS-BRAVO-02", "NOT_READY", hoursAgo(2))] } });
  expect(screen.queryByText("Stale")).toBeNull();
  expect(screen.queryByText(/run a readiness check before relying on Ready/)).toBeNull();
});

it("flags only the old row and states the share when most, not all, evaluations are stale", () => {
  withNow();
  const third = { ...evaluated("CLS-CHARLIE-03", "READY", hoursAgo(50)) };
  setup({ rows: { "CLS-ALPHA-01": [evaluated("CLS-ALPHA-01", "READY", hoursAgo(30))], "CLS-BRAVO-02": [evaluated("CLS-BRAVO-02", "NOT_READY", hoursAgo(1))], "CLS-CHARLIE-03": [third] } });
  expect(screen.getByText(/2 of 3 readiness evaluations are older than 24 h .*run a readiness check before relying on Ready\./)).toBeInTheDocument();
  expect(within(row("CLS-ALPHA-01")).getByText("Stale")).toBeInTheDocument();
  expect(within(row("CLS-BRAVO-02")).queryByText("Stale")).toBeNull();
});

it("does not warn when only a minority of evaluations is stale", () => {
  withNow();
  setup({ rows: { "CLS-ALPHA-01": [evaluated("CLS-ALPHA-01", "READY", hoursAgo(30))], "CLS-BRAVO-02": [evaluated("CLS-BRAVO-02", "NOT_READY", hoursAgo(1))], "CLS-CHARLIE-03": [evaluated("CLS-CHARLIE-03", "READY", hoursAgo(2))] } });
  expect(screen.queryByText(/run a readiness check before relying on Ready/)).toBeNull();
  expect(within(row("CLS-ALPHA-01")).getByText("Stale")).toBeInTheDocument();
});

it("an administrator outside the job window keeps the row actions and an enabled header button (PO 2026-10-20)", () => {
  render(<JobWindowContext.Provider value={{ open: false, label: "Next window: 18:00", adminBypass: true }}>
    <HaReadinessList clusters={clusters} rows={rows} running={null} busy={false} progress={{}} error={null} onOpen={vi.fn()} onRun={vi.fn()} onBulkRun={vi.fn()} />
  </JobWindowContext.Provider>);
  expect(within(table()).getByRole("columnheader", { name: "Actions" })).toBeVisible();
  expect(screen.getByTestId("toolbar-run-readiness")).toBeEnabled();
  expect(screen.getByTestId("toolbar-run-readiness")).not.toHaveTextContent("Next window");
});

it("keeps the mode on its own line without splitting its words", () => {
  setup({ rows: { "CLS-ALPHA-01": [{ ...rows["CLS-ALPHA-01"][0], mode: "active-passive" }] } });
  const mode = within(row("CLS-ALPHA-01")).getByText("Mode: Active/passive");
  expect(mode.tagName).toBe("DIV");
  expect(mode).toHaveStyle({ whiteSpace: "nowrap" });
});

it("shows no row action and no Actions column outside the job window; the header button carries the window (R6)", () => {
  const onRun = vi.fn();
  render(<JobWindowContext.Provider value={{ open: false, label: "Next window: 18:00" }}>
    <HaReadinessList clusters={clusters} rows={rows} running={null} busy={false} progress={{}} error={null} onOpen={vi.fn()} onRun={onRun} onBulkRun={vi.fn()} />
  </JobWindowContext.Provider>);
  expect(within(table()).queryByRole("columnheader", { name: "Actions" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Run pre-checks" })).toBeNull();
  expect(screen.getByTestId("toolbar-run-readiness")).toHaveTextContent("Run readiness check (2 clusters) · Next window: 18:00");
  expect(screen.getByTestId("toolbar-run-readiness")).toBeDisabled();
  // the legend is one line: the four counts and the rule sit in the same row
  const rule = document.querySelector("[data-readiness-rule]")!;
  expect(rule.parentElement).toContainElement(screen.getByLabelText("Readiness summary"));
});

// Post-release review 2026-10-20 (L1): the primary reason goes through the label map; a bare outcome token reads as the pill.
it("words a bare READY reason as Ready and keeps check names and sentences as they came", () => {
  const ready = summary("CLS-ALPHA-01", "READY");
  expect(primaryReasonWord({ ...ready, refusalReason: "CHECK_NOT_READY", readiness: { ...ready.readiness!, status: "NOT_READY", checks: [
    { checkNo: 4, title: "Configuration sync", member: "FW-TANGO-04", result: "FAIL", summary: "Config sync is disabled on 1 member(s): the running configurations are not kept equal", blocking: true, status: "FAIL", derived: {} }] } }))
    .toBe("Configuration sync: Config sync is disabled on 1 member(s): the running configurations are not kept equal");
  expect(primaryReasonWord({ ...ready, refusalReason: "READY" })).toBe("Ready");
  expect(primaryReasonWord({ ...ready, refusalReason: "NOT_READY" })).toBe("Not ready");
  expect(primaryReasonWord(ready)).toBe("No blocking conditions observed");
  expect(primaryReasonWord(summary("CLS-BRAVO-02", "NOT_READY"))).toBe("Installed policy parity");
  setup({ rows: { "CLS-ALPHA-01": [{ ...ready, refusalReason: "READY" }] } });
  const cells = within(within(table()).getByRole("row", { name: /CLS-ALPHA-01/ })).getAllByRole("cell");
  expect(cells.some((cell) => cell.textContent === "Ready")).toBe(true);
  expect(within(table()).queryByText("READY")).toBeNull();
});
it("gives the VSX parent row the chassis unit's own status, reason, age, checkbox and run button, and counts every unit", () => {
  const chassis: CpFailoverSummary = { ...summary("CLS-ALPHA-01", "NOT_READY"), unitKind: "VSX_CHASSIS",
    refusalReason: "VSX_CHASSIS_COMMANDS_NOT_APPROVED", readiness: { status: "UNKNOWN", observedAt: at, failedCheck: "", stopCode: "VSX_CHASSIS_COMMANDS_NOT_APPROVED", checks: [] } };
  const vs: CpFailoverSummary = { ...summary("CLS-ALPHA-01", "READY"), unitId: "opaque-vs-13", unitKind: "VIRTUAL_SYSTEM", virtual_system: "VS-ALPHA-13" };
  const vs2: CpFailoverSummary = { ...summary("CLS-ALPHA-01", "READY"), unitId: "opaque-vs-35", unitKind: "VIRTUAL_SYSTEM", virtual_system: "VS-ALPHA-35" };
  const { onRun, onBulkRun } = setup({ rows: { ...rows, "CLS-ALPHA-01": [chassis, vs, vs2] } });
  const parent = row("CLS-ALPHA-01");
  expect(within(parent).getByText("UNKNOWN")).toBeInTheDocument();
  expect(within(parent).getByText("VSX chassis")).toBeInTheDocument();
  expect(within(parent).getByText("VSX chassis reads await command approval")).toBeInTheDocument();
  expect(within(parent).queryByText("Not evaluated")).toBeNull();
  expect(within(parent).getByRole("checkbox", { name: "Select CLS-ALPHA-01" })).toBeEnabled();
  fireEvent.click(within(parent).getByRole("button", { name: "Run pre-checks" }));
  expect(onRun).toHaveBeenCalledWith(chassis);
  // R-13: By default the chips count clusters, matching the heading; switching to Readiness records counts each VS separately.
  expect(screen.getByRole("button", { name: "Ready: 0 clusters" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "UNKNOWN: 2 clusters" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Not ready: 1 clusters" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Readiness records (6)" }));
  expect(screen.getByText("4 clusters enrolled · showing 6 readiness records")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Ready: 2 readiness records" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "UNKNOWN: 2 readiness records" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Not ready: 1 readiness records" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Virtual systems in CLS-ALPHA-01" }));
  for (const name of ["VS-ALPHA-13", "VS-ALPHA-35"]) {
    const nested = row(`Virtual System ${name}`);
    expect(within(nested).getByText("Ready")).toBeInTheDocument();
    expect(within(nested).getByRole("checkbox", { name: `Select Virtual System ${name}` })).toBeEnabled();
  }
  fireEvent.click(within(row("Virtual System VS-ALPHA-35")).getByRole("button", { name: "Run pre-checks" }));
  expect(onRun).toHaveBeenLastCalledWith(vs2);
  fireEvent.click(screen.getByRole("checkbox", { name: "Select CLS-ALPHA-01" }));
  fireEvent.click(screen.getByRole("checkbox", { name: "Select Virtual System VS-ALPHA-13" }));
  fireEvent.click(screen.getByRole("button", { name: "Run pre-checks (2)" }));
  expect(onBulkRun).toHaveBeenCalledWith([chassis, vs]);
});
it("says in the chip tooltip whether clusters or readiness records are counted", async () => {
  setup();
  fireEvent.mouseOver(screen.getByRole("button", { name: "Ready: 1 clusters" }));
  expect(await screen.findByRole("tooltip")).toHaveTextContent("Counts enrolled clusters: each cluster counts once.");
  fireEvent.click(screen.getByRole("button", { name: "Readiness records (4)" }));
  fireEvent.mouseOver(screen.getByRole("button", { name: "Ready: 1 readiness records" }));
  expect(await screen.findByRole("tooltip")).toHaveTextContent("each VSX chassis and each of its Virtual Systems count separately");
});

it("opens on the status named by ?readiness= (the Overview's needs-attention line, UI review 2026-11-05 C1)", () => {
  window.history.replaceState(null, "", "/?screen=operations&readiness=NOT_READY");
  try {
    setup();
    expect(within(table()).queryByRole("row", { name: "CLS-ALPHA-01" })).toBeNull();
    expect(row("CLS-BRAVO-02")).toBeInTheDocument();
  } finally {
    window.history.replaceState(null, "", "/");
  }
});
it("lists a Cisco ASA pair as 'Readiness not available yet' with its vendor name and no run action", () => {
  const asa: CpFailoverSummary = { ...summary("CLS-DELTA-04", "READY"), readiness: null, canRunReadiness: false, readinessAvailable: false };
  setup({ rows: { ...rows, "CLS-DELTA-04": [asa] } });
  const item = screen.getByText("CLS-DELTA-04").closest("li")!;
  expect(within(item).getByText("Cisco ASA")).toBeInTheDocument();
  expect(within(item).getByText("Readiness not available yet")).toBeInTheDocument();
  expect(within(item).queryByRole("button")).toBeNull();
});
it("evaluates a Cisco ASA pair in the main list when its readiness is available, with the same run action", () => {
  const asa: CpFailoverSummary = { ...summary("CLS-DELTA-04", "READY"), vendor: "cisco_asa", readinessAvailable: true, canRunReadiness: true };
  const { onRun } = setup({ rows: { ...rows, "CLS-DELTA-04": [asa] } });
  expect(row("CLS-DELTA-04")).toBeInTheDocument();
  expect(screen.queryByText("Readiness not available yet")).toBeNull();
  expect(screen.queryByText(/Readiness pre-checks are not supported for these vendors/)).toBeNull();
  fireEvent.click(within(row("CLS-DELTA-04")).getByRole("button", { name: "Run pre-checks" }));
  expect(onRun).toHaveBeenCalledWith(asa);
  // Readiness only: no failover, approval or resume control is offered for the pair.
  expect(screen.queryByText(/Start failover|Resume return step/)).toBeNull();
});
it("keeps a Cisco ASA pair with readinessAvailable false in the listed-only block, without a run action", () => {
  const asa: CpFailoverSummary = { ...summary("CLS-DELTA-04", "READY"), vendor: "cisco_asa", readiness: null, canRunReadiness: false, readinessAvailable: false };
  setup({ rows: { ...rows, "CLS-DELTA-04": [asa] } });
  expect(within(table()).queryByRole("row", { name: "CLS-DELTA-04" })).toBeNull();
  const item = screen.getByText("CLS-DELTA-04").closest("li")!;
  expect(within(item).getByText("Readiness not available yet")).toBeInTheDocument();
  expect(within(item).queryByRole("button")).toBeNull();
});
const pairRow = (ref: string, vendor: string, roles: [string | null, string | null], state: "HEALTHY" | "DEGRADED" | "UNKNOWN", reason: string | null): CpFailoverSummary => ({
  ...summary(ref, "READY"), readiness: null, canRunReadiness: false, readinessAvailable: false,
  members: [{ device_id: `${ref}-m1`, hostname: `FW-${ref}-M1`, ha_role: roles[0] }, { device_id: `${ref}-m2`, hostname: `FW-${ref}-M2`, ha_role: roles[1] }],
  haView: { vendor, state, reason, sync: "UNKNOWN", syncReason: "NOT_COLLECTED",
    members: [{ device_id: `${ref}-m1`, role: roles[0] ?? "unknown" }, { device_id: `${ref}-m2`, role: roles[1] ?? "unknown" }] },
});
it.each([
  ["cisco_asa", ["active", "standby"], "HEALTHY", null, "Pair healthy", ["Active", "Standby"]],
  ["cisco_asa", ["active", "failed"], "DEGRADED", "MEMBER_FAILED", "Pair degraded", ["Active", "Failed"]],
  ["fortinet", ["primary", "secondary"], "HEALTHY", null, "Pair healthy", ["Primary", "Secondary"]],
  ["fortinet", ["primary", null], "UNKNOWN", "ROLE_NOT_OBSERVED", "Pair state unknown", ["Primary", "Unknown"]],
] as const)("shows %s pair members, roles, pair state and sync as unknown", (vendor, roles, state, reason, stateWord, words) => {
  const ref = vendor === "cisco_asa" ? "CLS-DELTA-04" : "CLS-ECHO-05";
  const members = cluster(ref, vendor).members;
  setup({ clusters: [cluster("CLS-ALPHA-01"), { ref, title: ref, members }], rows: { ...rows, [ref]: [pairRow(ref, vendor, [roles[0], roles[1]], state, reason)] } });
  const item = screen.getByText(ref).closest("li")!;
  expect(within(item).getByText(stateWord)).toBeInTheDocument();
  expect(within(item).getByText(`FW-${ref}-M1: ${words[0]}`)).toBeInTheDocument();
  expect(within(item).getByText(`FW-${ref}-M2: ${words[1]}`)).toBeInTheDocument();
  expect(within(item).getByText("Sync: Unknown (not collected)")).toBeInTheDocument();
  expect(within(item).queryByRole("button")).toBeNull();
});
it("evaluates a single-address FortiGate cluster in the main list when its readiness is available, with the same run action", () => {
  const fgt: CpFailoverSummary = { ...summary("CLS-DELTA-04", "READY"), vendor: "fortinet", readinessAvailable: true, canRunReadiness: true };
  const { onRun } = setup({ rows: { ...rows, "CLS-DELTA-04": [fgt] } });
  expect(row("CLS-DELTA-04")).toBeInTheDocument();
  expect(screen.queryByText("Readiness not available yet")).toBeNull();
  fireEvent.click(within(row("CLS-DELTA-04")).getByRole("button", { name: "Run pre-checks" }));
  expect(onRun).toHaveBeenCalledWith(fgt);
  expect(screen.queryByText(/Start failover|Resume return step/)).toBeNull();
});
it("shows the single FortiGate's roles from check 1 (primary active, secondary standby, as the primary reports) and none without it", () => {
  const single = { ref: "CLS-TANGO-05", title: "CLS-TANGO-05", members: [
    { device_id: "fgt-m1", hostname: "FW-TANGO-05", vendor_hint: "fortinet", ha_role: null, inventory_collected_at: at }] as DeviceSummary[] };
  const check = (result: "PASS" | "UNKNOWN") => ({ checkNo: 1, title: "HA mode and members", member: "Member 1", device_id: "fgt-m1", result,
    summary: "x", blocking: true, status: result, derived: {} });
  const unit = (result: "PASS" | "UNKNOWN"): CpFailoverSummary => ({ ...summary("CLS-TANGO-05", "READY"), vendor: "fortinet", readinessAvailable: true,
    members: single.members.map(m => ({ device_id: m.device_id, hostname: m.hostname, ha_role: null })),
    readiness: { status: "READY", observedAt: at, failedCheck: "", checks: [check(result)] } });
  const view = (result: "PASS" | "UNKNOWN") => render(<HaReadinessList clusters={[single]} rows={{ "CLS-TANGO-05": [unit(result)] }} running={null} busy={false}
    progress={{}} error={null} onOpen={vi.fn()} onRun={vi.fn()} onBulkRun={vi.fn()} />);
  const first = view("PASS");
  expect(within(row("CLS-TANGO-05")).getByText(/Active: FW-TANGO-05/).textContent).toContain("Standby: Secondary (as the primary reports)");
  first.unmount();
  view("UNKNOWN");
  expect(within(row("CLS-TANGO-05")).getByText(/Active:/).textContent).toBe("Active: Not observed ↔ Standby: Not observed");
});
it("offers the chassis failover its mode allows on the VSX parent row, or says why none", () => {
  const base: CpFailoverSummary = { ...summary("CLS-ALPHA-01", "READY"), unitKind: "VSX_CHASSIS" };
  const vs: CpFailoverSummary = { ...summary("CLS-ALPHA-01", "READY"), unitId: "opaque-vs-13", unitKind: "VIRTUAL_SYSTEM", virtual_system: "VS-ALPHA-13" };
  const { onOpen, unmount } = setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...base, chassisMode: "VSLS", chassisAction: "FAILOVER_ALL_VIRTUAL_SYSTEMS" }, vs] } });
  fireEvent.click(within(row("CLS-ALPHA-01")).getByRole("button", { name: "Fail over all virtual systems" }));
  expect(onOpen).toHaveBeenCalledWith("CLS-ALPHA-01", base.unitId);
  unmount();
  setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...base, chassisMode: "HA", chassisAction: "FAILOVER" }, vs] } });
  expect(within(row("CLS-ALPHA-01")).getByRole("button", { name: "Fail over the chassis" })).toBeInTheDocument();
  cleanup();
  setup({ rows: { ...rows, "CLS-ALPHA-01": [{ ...base, failoverRefusal: "VSX_CHASSIS_MODE_UNKNOWN" }, vs] } });
  expect(within(row("CLS-ALPHA-01")).getByText(/VSX chassis mode not known/)).toHaveAttribute("title", "VSX_CHASSIS_MODE_UNKNOWN");
  expect(within(row("CLS-ALPHA-01")).queryByRole("button", { name: /Fail over/ })).toBeNull();
});
it("renders Cisco ASA and FortiGate pairs as first-class rows with readiness roles, inventory fallback, check table, vendor filter, single chip counts and execution-disabled notice", () => {
  withNow();
  const observedAt = hoursAgo(1);
  const asaCluster = { ref: "CLS-DELTA-04", title: "CLS-DELTA-04", members: [
    { device_id: "asa-m1", hostname: "FW-DELTA-04-M1", vendor_hint: "cisco_asa", ha_role: "active", inventory_collected_at: at },
    { device_id: "asa-m2", hostname: "FW-DELTA-04-M2", vendor_hint: "cisco_asa", ha_role: "standby", inventory_collected_at: at },
  ] as DeviceSummary[] };
  const fgtCluster = { ref: "CLS-ECHO-05", title: "CLS-ECHO-05", members: [
    { device_id: "fgt-m1", hostname: "FW-ECHO-05-M1", vendor_hint: "fortinet", ha_role: "primary", inventory_collected_at: at },
    { device_id: "fgt-m2", hostname: "FW-ECHO-05-M2", vendor_hint: "fortinet", ha_role: "secondary", inventory_collected_at: at },
  ] as DeviceSummary[] };
  // ASA readiness check 1 observed roles swapped after failover (M2 active, M1 standby); check 5 failed with internal reason SYNC_NOT_DONE.
  const asaRow: CpFailoverSummary = {
    ...summary("CLS-DELTA-04", "NOT_READY"), vendor: "cisco_asa", readinessAvailable: true, canRunReadiness: true,
    refusalReason: "SYNC_NOT_DONE", failoverRefusal: "FAILOVER_VENDOR_NOT_AVAILABLE",
    members: asaCluster.members.map(m => ({ device_id: m.device_id, hostname: m.hostname, ha_role: m.ha_role })),
    readiness: {
      status: "NOT_READY", observedAt, failedCheck: "Configuration sync", stopCode: "SYNC_NOT_DONE",
      checks: [
        { checkNo: 1, title: "Failover state and roles", member: "Member 1", device_id: "asa-m1", result: "PASS", status: "PASS",
          summary: "One Active and one Standby Ready; both views agree", blocking: true, observedAt, derived: { role: "STANDBY" } },
        { checkNo: 1, title: "Failover state and roles", member: "Member 2", device_id: "asa-m2", result: "PASS", status: "PASS",
          summary: "One Active and one Standby Ready; both views agree", blocking: true, observedAt, derived: { role: "ACTIVE" } },
        { checkNo: 5, title: "Configuration sync", member: "Member 1", device_id: "asa-m1", result: "FAIL", status: "FAIL",
          summary: "Configuration not synchronised", blocking: true, observedAt, derived: { reason: "SYNC_NOT_DONE" } },
        { checkNo: 5, title: "Configuration sync", member: "Member 2", device_id: "asa-m2", result: "FAIL", status: "FAIL",
          summary: "Configuration not synchronised", blocking: true, observedAt, derived: { reason: "SYNC_NOT_DONE" } },
      ],
    },
  };
  // FortiGate pair without check-1 roles yet falls back to inventory roles (primary/secondary) labelled "(inventory)".
  const fgtRow: CpFailoverSummary = {
    ...summary("CLS-ECHO-05", "READY"), vendor: "fortinet", readinessAvailable: true, canRunReadiness: true,
    refusalReason: null, failoverRefusal: "FAILOVER_VENDOR_NOT_AVAILABLE",
    members: fgtCluster.members.map(m => ({ device_id: m.device_id, hostname: m.hostname, ha_role: m.ha_role })),
    readiness: {
      status: "READY", observedAt, failedCheck: "",
      checks: [
        { checkNo: 4, title: "Configuration sync", member: "Member 1", device_id: "fgt-m1", result: "PASS", status: "PASS",
          summary: "Both members in sync and the cluster checksums equal", blocking: true, observedAt, derived: {} },
      ],
    },
  };
  const allClusters = [clusters[0], clusters[1], asaCluster, fgtCluster];
  const allRows = { ...rows, "CLS-DELTA-04": [asaRow], "CLS-ECHO-05": [fgtRow] };
  const { onRun } = setup({ clusters: allClusters, rows: allRows });

  // Fleet chips count each pair once in both Clusters and Readiness records views; none in Not supported.
  expect(screen.getByRole("button", { name: "Ready: 2 clusters" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Not ready: 2 clusters" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Not supported: 0 clusters" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Clusters (4)" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Readiness records (4)" })).toBeInTheDocument();

  // ASA row: status, primary reason from lead blocking check, readiness-observed roles with time, execution-disabled notice, Run pre-checks.
  const asa = row("CLS-DELTA-04");
  expect(within(asa).getByText("Not ready")).toBeInTheDocument();
  expect(within(asa).getByText("Configuration sync: Configuration not synchronised")).toBeInTheDocument();
  expect(within(asa).getByText(/Active: FW-DELTA-04-M2/)).toHaveTextContent("Standby: FW-DELTA-04-M1 (1 h ago)");
  expect(asa.querySelector('[data-role-source="readiness"]')).toHaveTextContent("(1 h ago)");
  expect(within(asa).getByText("Failover execution not enabled for Cisco ASA")).toBeInTheDocument();
  fireEvent.click(within(asa).getByRole("button", { name: "Run pre-checks" }));
  expect(onRun).toHaveBeenCalledWith(asaRow);

  // Expand ASA row: same ReadinessChecksTable component ordered active-first with readiness roles and time.
  fireEvent.click(within(asa).getByRole("button", { name: "Checks for CLS-DELTA-04" }));
  const asaDetail = screen.getByRole("region", { name: "Checks for CLS-DELTA-04" });
  expect(within(asaDetail).getAllByRole("columnheader").map(c => c.textContent)).toEqual([
    "Check", "FW-DELTA-04-M2 · active (readiness, 1 h ago)", "FW-DELTA-04-M1 · standby (readiness, 1 h ago)",
  ]);

  // FortiGate row: status, primary reason, inventory fallback roles labelled "(inventory)", execution-disabled notice, check table on expand.
  const fgt = row("CLS-ECHO-05");
  expect(within(fgt).getByText("Ready")).toBeInTheDocument();
  expect(within(fgt).getByText("No blocking conditions observed")).toBeInTheDocument();
  expect(within(fgt).getByText(/Active: FW-ECHO-05-M1/)).toHaveTextContent("Standby: FW-ECHO-05-M2 (inventory)");
  expect(fgt.querySelector('[data-role-source="inventory"]')).toHaveTextContent("(inventory)");
  expect(within(fgt).getByText("Failover execution not enabled for FortiGate")).toBeInTheDocument();
  fireEvent.click(within(fgt).getByRole("button", { name: "Checks for CLS-ECHO-05" }));
  const fgtDetail = screen.getByRole("region", { name: "Checks for CLS-ECHO-05" });
  expect(within(fgtDetail).getAllByRole("columnheader").map(c => c.textContent)).toEqual([
    "Check", "FW-ECHO-05-M1 · primary (inventory)", "FW-ECHO-05-M2 · secondary (inventory)",
  ]);

  // Vendor filter narrows to Cisco ASA and Fortinet rows respectively.
  fireEvent.mouseDown(screen.getByRole("combobox", { name: "Vendor" }));
  fireEvent.click(screen.getByRole("option", { name: "Cisco ASA" }));
  expect(row("CLS-DELTA-04")).toBeInTheDocument();
  expect(screen.queryByRole("row", { name: "CLS-ECHO-05" })).toBeNull();
  fireEvent.mouseDown(screen.getByRole("combobox", { name: "Vendor" }));
  fireEvent.click(screen.getByRole("option", { name: "Fortinet" }));
  expect(row("CLS-ECHO-05")).toBeInTheDocument();
  expect(screen.queryByRole("row", { name: "CLS-DELTA-04" })).toBeNull();
});
it("derives readiness roles for an ASA pair from check 10 standby marker and for a two-member FortiGate pair from the primary check-1 PASS", () => {
  withNow();
  const observedAt = hoursAgo(2);
  const asaCluster = { ref: "CLS-DELTA-04", title: "CLS-DELTA-04", members: [
    { device_id: "asa-m1", hostname: "FW-DELTA-04-M1", vendor_hint: "cisco_asa", ha_role: "active", inventory_collected_at: at },
    { device_id: "asa-m2", hostname: "FW-DELTA-04-M2", vendor_hint: "cisco_asa", ha_role: "standby", inventory_collected_at: at },
  ] as DeviceSummary[] };
  const fgtCluster = { ref: "CLS-ECHO-05", title: "CLS-ECHO-05", members: [
    { device_id: "fgt-m1", hostname: "FW-ECHO-05-M1", vendor_hint: "fortinet", ha_role: "secondary", inventory_collected_at: at },
    { device_id: "fgt-m2", hostname: "FW-ECHO-05-M2", vendor_hint: "fortinet", ha_role: "primary", inventory_collected_at: at },
  ] as DeviceSummary[] };
  const asaRow: CpFailoverSummary = {
    ...summary("CLS-DELTA-04", "READY"), vendor: "cisco_asa", readinessAvailable: true, canRunReadiness: true,
    members: asaCluster.members.map(m => ({ device_id: m.device_id, hostname: m.hostname, ha_role: m.ha_role })),
    readiness: {
      status: "READY", observedAt, failedCheck: "",
      checks: [
        { checkNo: 1, title: "Failover state and roles", member: "Member 1", device_id: "asa-m1", result: "PASS", status: "PASS",
          summary: "One Active and one Standby Ready; both views agree", blocking: true, observedAt, derived: { oneActiveOneStandbyReady: true } },
        { checkNo: 1, title: "Failover state and roles", member: "Member 2", device_id: "asa-m2", result: "PASS", status: "PASS",
          summary: "One Active and one Standby Ready; both views agree", blocking: true, observedAt, derived: { oneActiveOneStandbyReady: true } },
        { checkNo: 10, title: "Traffic and ARP (information)", member: "Member 1", device_id: "asa-m1", result: "NOT_EVALUATED", status: "NOT_EVALUATED",
          summary: "Read on the active member only", blocking: false, observedAt, derived: { reason: "NOT_ACTIVE_MEMBER" } },
        { checkNo: 10, title: "Traffic and ARP (information)", member: "Member 2", device_id: "asa-m2", result: "PASS", status: "PASS",
          summary: "2 KB/s, 8 ARP entries", blocking: false, observedAt, derived: {} },
      ],
    },
  };
  const fgtRow: CpFailoverSummary = {
    ...summary("CLS-ECHO-05", "READY"), vendor: "fortinet", readinessAvailable: true, canRunReadiness: true,
    members: fgtCluster.members.map(m => ({ device_id: m.device_id, hostname: m.hostname, ha_role: m.ha_role })),
    readiness: {
      status: "READY", observedAt, failedCheck: "",
      checks: [
        { checkNo: 1, title: "HA mode and members", member: "Member 1", device_id: "fgt-m2", result: "PASS", status: "PASS",
          summary: "Active-passive, one primary and one secondary, health OK, identity matches (as the primary reports)", blocking: true, observedAt, derived: {} },
      ],
    },
  };
  setup({ clusters: [asaCluster, fgtCluster], rows: { "CLS-DELTA-04": [asaRow], "CLS-ECHO-05": [fgtRow] } });
  expect(within(row("CLS-DELTA-04")).getByText(/Active: FW-DELTA-04-M2/)).toHaveTextContent("Standby: FW-DELTA-04-M1 (2 h ago)");
  expect(within(row("CLS-ECHO-05")).getByText(/Active: FW-ECHO-05-M2/)).toHaveTextContent("Standby: FW-ECHO-05-M1 (2 h ago)");
});
