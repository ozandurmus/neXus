import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { CpFailoverSummary, DeviceSummary } from "../src/auth/adminApi";
import { HaReadinessList } from "../src/screens/HaReadinessList";
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
  for (const name of ["Ready", "Not ready", "Unknown", "Not supported"]) expect(screen.getByRole("button", { name: `${name}: 1 clusters` })).toHaveAttribute("aria-pressed", "false");
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
  for (const [label, name] of [["Ready", "CLS-ALPHA-01"], ["Not ready", "CLS-BRAVO-02"], ["Unknown", "CLS-CHARLIE-03"]]) {
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
  fireEvent.click(screen.getByRole("option", { name: "Palo Alto Networks" }));
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
  expect(screen.getByRole("button", { name: "Unknown: 1 clusters" })).toBeInTheDocument();
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
  expect(item.querySelector('[title^="Roles observed:"]')).toHaveAttribute("title", "Roles observed: 2026-10-02 12:00:00 GMT+3");
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
it("hides bulk and run actions for aiview and reacts to permission removal", () => {
  const { rerender } = setup();
  fireEvent.click(screen.getByRole("checkbox", { name: "Select all visible" }));
  const readonlyRows = Object.fromEntries(Object.entries(rows).map(([ref, units]) => [ref, units.map(unit => ({ ...unit, masked: true, canRunReadiness: true }))]));
  rerender(<HaReadinessList clusters={clusters} rows={readonlyRows} running={null} busy={false} progress={{}} error={null} onOpen={vi.fn()} onRun={vi.fn()} onBulkRun={vi.fn()} />);
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
  expect(within(detail).getAllByRole("columnheader").map(cell => cell.textContent)).toEqual(["Check", "FW-CLS-ALPHA-01-M1 · active", "FW-CLS-ALPHA-01-M2 · standby"]);
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
