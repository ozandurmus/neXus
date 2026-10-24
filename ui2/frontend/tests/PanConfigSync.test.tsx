import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import type { CpFailoverSummary, DeviceSummary, PanConfigSyncFacts, PanConfigSyncRecord } from "../src/auth/adminApi";

/** PAN HA configuration sync (docs/design/PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §6): the server decides; the UI shows. */
const api = vi.hoisted(() => ({ request: vi.fn(), get: vi.fn(), run: vi.fn() }));
vi.mock("../src/auth/adminApi", async (original) => ({ ...(await original<typeof import("../src/auth/adminApi")>()),
  requestPanConfigSync: api.request, getPanConfigSync: api.get, getCpFailoverRun: api.run }));
import { PanConfigSyncAction, CONFIG_SYNC_COMMAND } from "../src/screens/PanConfigSyncAction";
import { HaReadinessList } from "../src/screens/HaReadinessList";
import { ReadinessChecksTable } from "../src/screens/ReadinessChecksTable";

const at = new Date().toISOString();
const ids = ["opaque-" + "a1", "opaque-" + "b2"];
const members = [{ device_id: ids[0], hostname: "FW-BRAVO-02", ha_role: "active" }, { device_id: ids[1], hostname: "FW-TANGO-04", ha_role: "passive" }];
const facts = (over: Partial<PanConfigSyncFacts> = {}): PanConfigSyncFacts => ({ offered: true, canRun: true, refusal: null,
  sourceRef: ids[0], targetRef: ids[1], readinessObservedAt: at, latest: null, ...over });
const unit = (over: Partial<PanConfigSyncFacts> = {}, vendor: "palo_alto" | "check_point" = "palo_alto") => ({
  clusterId: "opaque-cluster", unitId: "opaque-cluster", vendor, masked: false, members, configSync: facts(over) });
const record = (state: PanConfigSyncRecord["state"], over: Partial<PanConfigSyncRecord> = {}): PanConfigSyncRecord => ({
  syncId: "sync-opaque", state, outcome: null, deviceStatus: null, sourceRef: ids[0], targetRef: ids[1], requestedAt: at,
  startedAt: null, finishedAt: null, mutationPossible: false, readinessRunId: null, readinessRefusal: null, ...over });
beforeEach(() => { vi.resetAllMocks(); });

it("is shown only when the server offers it, and never for Check Point", () => {
  const { unmount } = render(<PanConfigSyncAction unit={unit()} />);
  expect(screen.getByRole("button", { name: "Sync configuration to passive" })).toBeEnabled();
  unmount();
  // Operator, viewer and aiview: the server sends canRun false; nothing is rendered.
  const hidden = render(<PanConfigSyncAction unit={unit({ canRun: false })} />);
  expect(hidden.container).toBeEmptyDOMElement();
  hidden.unmount();
  const cp = render(<PanConfigSyncAction unit={unit({}, "check_point")} />);
  expect(cp.container).toBeEmptyDOMElement();
  cp.unmount();
  // A server refusal disables the button and says why.
  render(<PanConfigSyncAction unit={unit({ refusal: "READINESS_NOT_FRESH" })} />);
  expect(screen.getByRole("button", { name: "Sync configuration to passive" })).toBeDisabled();
  expect(screen.getByText("The latest pre-checks are older than 60 minutes. Run the pre-checks again first.")).toBeInTheDocument();
});

it("confirms with the source (active), the target (passive) and the command, then shows progress and the re-run result", async () => {
  api.request.mockResolvedValue(record("QUEUED"));
  api.get.mockResolvedValueOnce(record("SYNCING", { mutationPossible: true }))
    .mockResolvedValue(record("DONE", { outcome: "CONFIG_SYNC_REQUESTED", deviceStatus: "success", mutationPossible: true, readinessRunId: "run-opaque" }));
  api.run.mockResolvedValue({ runId: "run-opaque", state: "DONE", checks: [
    { checkNo: 4, phase: "pre", status: "PASS" }, { checkNo: 4, phase: "pre", status: "PASS" }] });
  const done = vi.fn();
  render(<PanConfigSyncAction unit={unit()} onDone={done} pollMs={0} />);
  fireEvent.click(screen.getByRole("button", { name: "Sync configuration to passive" }));
  const dialog = screen.getByRole("dialog");
  expect(within(dialog).getByText("Source (active): FW-BRAVO-02")).toBeInTheDocument();
  expect(within(dialog).getByText("Target (passive): FW-TANGO-04")).toBeInTheDocument();
  expect(within(dialog).getByText(CONFIG_SYNC_COMMAND)).toBeInTheDocument();
  expect(within(dialog).getByText(/It sends nothing unless FW-BRAVO-02 is still active, FW-TANGO-04 is still passive/)).toBeInTheDocument();
  const confirm = within(dialog).getByRole("button", { name: "Sync configuration" });
  fireEvent.click(confirm); fireEvent.click(confirm);
  await waitFor(() => expect(done).toHaveBeenCalled());
  expect(api.request).toHaveBeenCalledTimes(1);
  expect(api.request).toHaveBeenCalledWith(expect.objectContaining({ unitId: "opaque-cluster", clusterId: "opaque-cluster" }), ids[0], ids[1]);
  expect(api.run).toHaveBeenCalledWith("run-opaque", "palo_alto");
  expect(screen.getByText("The active member accepted the sync command.")).toBeInTheDocument();
  expect(screen.getByText("Pre-checks finished: Configuration sync passes.")).toBeInTheDocument();
});

it("shows a refusal or a stopped job in plain words", async () => {
  api.request.mockRejectedValueOnce({ status: 409, body: { code: "CONFIG_SYNC_ALREADY_ACTIVE" } });
  render(<PanConfigSyncAction unit={unit()} pollMs={0} />);
  fireEvent.click(screen.getByRole("button", { name: "Sync configuration to passive" }));
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Sync configuration" }));
  expect(await screen.findByText("A configuration sync is already running for this unit.")).toBeInTheDocument();
  api.request.mockResolvedValue(record("QUEUED"));
  api.get.mockResolvedValue(record("STOPPED", { outcome: "ROLES_CHANGED", readinessRunId: null, readinessRefusal: null }));
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Sync configuration" }));
  expect(await screen.findByText(/Stopped before the write: the members' roles are not the ones shown/)).toBeInTheDocument();
});

it("the readiness table renders the action in the check 4 row only", () => {
  const checks = [1, 4, 5].map(no => ({ checkNo: no, title: `Check ${no}`, member: "Member 1", result: (no === 4 ? "FAIL" : "PASS") as "FAIL" | "PASS",
    summary: "s", blocking: true, device_id: ids[0] }));
  const action = vi.fn((no: number, failed: boolean) => no === 4 ? <span>{`action ${no} ${failed}`}</span> : null);
  render(<ReadinessChecksTable checks={checks} members={members} rowAction={action} />);
  expect(screen.getByText("action 4 true")).toBeInTheDocument();
  expect(screen.queryByText(/action 1/)).toBeNull();
});

it("the HA readiness list offers it under the failed Configuration sync row of a Palo Alto unit", () => {
  const cluster = { ref: "CLS-BRAVO-02", title: "CLS-BRAVO-02", members: members.map(m => ({ ...m, vendor_hint: "palo_alto", inventory_collected_at: at })) as unknown as DeviceSummary[] };
  const row = (canRun: boolean): CpFailoverSummary => ({ clusterId: "opaque-cluster", unitId: "opaque-cluster", cluster_member_ref: cluster.ref,
    vendor: "palo_alto", activeWindow: false, lastRunAt: null, lastRunState: null, lastRunOutcome: null, masked: false, canRunReadiness: true,
    members, configSync: facts({ canRun }),
    readiness: { status: "NOT_READY", observedAt: at, failedCheck: "Configuration sync", checks: [4, 5].flatMap(no => ids.map((id, i) => ({
      checkNo: no, title: no === 4 ? "Configuration sync" : "Session synchronization", member: `Member ${i + 1}`, device_id: id,
      result: (no === 4 ? "FAIL" : "PASS") as "FAIL" | "PASS", status: (no === 4 ? "FAIL" : "PASS") as "FAIL" | "PASS", summary: "s", blocking: true, derived: {} }))) } });
  const props = { clusters: [cluster], running: null, busy: false, progress: {}, error: null, onOpen: vi.fn(), onRun: vi.fn(), onBulkRun: vi.fn() };
  const { unmount } = render(<HaReadinessList {...props} rows={{ [cluster.ref]: [row(true)] }} />);
  fireEvent.click(screen.getByRole("button", { name: "Checks for CLS-BRAVO-02" }));
  const region = screen.getByRole("region", { name: "Checks for CLS-BRAVO-02" });
  expect(within(region).getAllByRole("button", { name: "Sync configuration to passive" })).toHaveLength(1);
  unmount();
  render(<HaReadinessList {...props} rows={{ [cluster.ref]: [row(false)] }} />);
  fireEvent.click(screen.getByRole("button", { name: "Checks for CLS-BRAVO-02" }));
  expect(screen.queryByRole("button", { name: "Sync configuration to passive" })).toBeNull();
});

it("a failed status read after the request was accepted is retried, never reported as a failed request", async () => {
  api.request.mockResolvedValue(record("QUEUED"));
  api.get.mockRejectedValueOnce({ status: 0, body: { error: "TIMEOUT" } })
    .mockResolvedValue(record("STOPPED", { outcome: "OUTCOME_UNKNOWN", mutationPossible: true, readinessRunId: "run-opaque" }));
  api.run.mockResolvedValue({ runId: "run-opaque", state: "DONE", checks: [{ checkNo: 4, phase: "pre", status: "FAIL" }] });
  const done = vi.fn();
  render(<PanConfigSyncAction unit={unit()} onDone={done} pollMs={0} />);
  fireEvent.click(screen.getByRole("button", { name: "Sync configuration to passive" }));
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Sync configuration" }));
  await waitFor(() => expect(done).toHaveBeenCalled());
  expect(screen.queryByText("Request failed")).toBeNull();
  expect(screen.getByText("The sync command's outcome is uncertain. It is not resent.")).toBeInTheDocument();
  expect(screen.getByText("Pre-checks finished: Configuration sync still fails.")).toBeInTheDocument();
});

it("a sync already open when the page loads is shown and followed to its end", async () => {
  api.get.mockResolvedValue(record("DONE", { outcome: "CONFIG_SYNC_REQUESTED", deviceStatus: "success", mutationPossible: true,
    readinessRunId: null, readinessRefusal: "RUN_ALREADY_ACTIVE" }));
  const done = vi.fn();
  render(<PanConfigSyncAction unit={unit({ latest: record("SYNCING", { mutationPossible: true }) })} onDone={done} pollMs={0} />);
  expect(screen.getByText("Sending the sync command to the active member")).toBeInTheDocument();
  await waitFor(() => expect(done).toHaveBeenCalled());
  expect(api.request).not.toHaveBeenCalled();
  expect(screen.getByText("The active member accepted the sync command.")).toBeInTheDocument();
  expect(screen.getByText(/The pre-checks were not re-run \(Another run is in progress on this unit\.\)/)).toBeInTheDocument();
});
