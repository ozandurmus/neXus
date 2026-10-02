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
  lastRunOutcome: null, masked: true, canRunReadiness: true,
  readiness: { status, observedAt: at, failedCheck: status === "NOT_READY" ? "Installed policy parity" : "", checks: [] },
});
const rows = { "CLS-ALPHA-01": [summary("CLS-ALPHA-01", "READY")], "CLS-BRAVO-02": [summary("CLS-BRAVO-02", "NOT_READY")] };
function setup(extra = {}) {
  const onOpen = vi.fn(), onRun = vi.fn();
  render(<HaReadinessList clusters={clusters} rows={rows} running={null} error={null} onOpen={onOpen} onRun={onRun} {...extra} />);
  return { onOpen, onRun };
}
afterEach(() => vi.useRealTimers());
it("counts clusters with vendor breakdown and sorts not ready, unknown, ready", () => {
  setup();
  for (const name of ["Ready", "Not ready", "Unknown", "Not supported"]) expect(screen.getByRole("button", { name: `${name}: 1 clusters` })).toHaveAttribute("aria-pressed", "false");
  expect(screen.getByText("Palo Alto Networks 1")).toBeInTheDocument();
  expect(within(screen.getByRole("list", { name: "HA clusters" })).getAllByRole("listitem").map(e => e.getAttribute("aria-label"))).toEqual(["CLS-BRAVO-02", "CLS-CHARLIE-03", "CLS-ALPHA-01"]);
  expect(screen.queryByText("NOT_READY")).toBeNull();
});
it("toggles tiles and combines filters with cluster/member search", () => {
  setup();
  for (const [label, name] of [["Ready", "CLS-ALPHA-01"], ["Not ready", "CLS-BRAVO-02"], ["Unknown", "CLS-CHARLIE-03"]]) {
    const tile = screen.getByRole("button", { name: `${label}: 1 clusters` });
    fireEvent.click(tile);
    expect(tile).toHaveAttribute("aria-pressed", "true");
    expect(within(screen.getByRole("list", { name: "HA clusters" })).getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByRole("listitem", { name })).toBeInTheDocument();
    fireEvent.click(tile);
    expect(tile).toHaveAttribute("aria-pressed", "false");
  }
  fireEvent.change(screen.getByRole("textbox"), { target: { value: " fw-cls-alpha-01-m2 " } });
  expect(screen.getByRole("listitem", { name: "CLS-ALPHA-01" })).toBeInTheDocument();
  expect(screen.queryByRole("listitem", { name: "CLS-BRAVO-02" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Not ready: 1 clusters" }));
  expect(screen.getByText("No clusters match these filters")).toBeInTheDocument();
});
it("collapses unsupported vendors with one explanation and opens them via their tile", () => {
  setup();
  const group = screen.getByText("Not supported · 1 clusters").closest("details")!;
  expect(group).not.toHaveAttribute("open");
  expect(within(group).queryByText("Unknown")).toBeNull();
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
it("nests VS rows with independent details and run actions without double counting", () => {
  const vs = { ...summary("CLS-ALPHA-01", "NOT_READY"), unitId: "opaque-vs", virtual_system: "VS-ALPHA-07" };
  const { onOpen, onRun } = setup({ rows: { ...rows, "CLS-ALPHA-01": [...rows["CLS-ALPHA-01"], vs] } });
  const children = within(screen.getByRole("listitem", { name: "CLS-ALPHA-01" })).getByRole("list", { name: "Virtual systems in CLS-ALPHA-01" });
  fireEvent.click(within(children).getByRole("button", { name: "Run pre-checks" }));
  expect(onRun).toHaveBeenCalledWith(vs);
  expect(onOpen).not.toHaveBeenCalled();
  fireEvent.click(within(children).getByRole("button", { name: "Open Virtual System VS-ALPHA-07" }));
  expect(onOpen).toHaveBeenCalledWith("CLS-ALPHA-01", "opaque-vs");
  expect(screen.getByRole("button", { name: "Ready: 1 clusters" })).toBeInTheDocument();
});
it("shows relative evaluation time and hover-only absolute and roles-observed times", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-02T09:35:00Z")); setup();
  const card = screen.getByRole("listitem", { name: "CLS-BRAVO-02" });
  expect(within(card).getByText("35 min ago")).toHaveAttribute("datetime", at);
  expect(within(card).getByText("35 min ago")).toHaveAttribute("title", expect.stringContaining(at));
  expect(card.querySelector('[title^="Roles observed:"]')).toHaveAttribute("title", "Roles observed: 2026-10-02 12:00:00 GMT+3");
  act(() => vi.advanceTimersByTime(60_000));
  expect(within(card).getByText("36 min ago")).toBeInTheDocument();
});
it("shows progress and respects readiness permissions", () => {
  setup({ running: "CLS-ALPHA-01", rows: { ...rows, "CLS-BRAVO-02": [{ ...rows["CLS-BRAVO-02"][0], canRunReadiness: false }] } });
  const button = within(screen.getByRole("listitem", { name: "CLS-ALPHA-01" })).getByRole("button", { name: "Run pre-checks" });
  expect(button).toBeDisabled(); expect(button).toHaveAttribute("aria-busy", "true");
  expect(within(button).getByRole("progressbar")).toBeInTheDocument();
  expect(within(screen.getByRole("listitem", { name: "CLS-BRAVO-02" })).queryByRole("button", { name: "Run pre-checks" })).toBeNull();
});
