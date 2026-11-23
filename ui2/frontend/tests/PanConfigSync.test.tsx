import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import type { CpFailoverSummary, DeviceSummary, PanConfigSyncFacts, PanConfigSyncRecord } from "../src/auth/adminApi";

/** PAN HA configuration sync (docs/design/PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §6): the server decides; the UI shows. */
const api = vi.hoisted(() => ({ request: vi.fn(), get: vi.fn(), run: vi.fn() }));
vi.mock("../src/auth/adminApi", async (original) => ({ ...(await original<typeof import("../src/auth/adminApi")>()),
  requestPanConfigSync: api.request, getPanConfigSync: api.get, getCpFailoverRun: api.run }));
import { PanConfigSyncAction, CONFIG_SYNC_COMMAND, configSyncSettlingText } from "../src/screens/PanConfigSyncAction";
import { HaReadinessList } from "../src/screens/HaReadinessList";
import { ReadinessChecksTable } from "../src/screens/ReadinessChecksTable";
import { ChecksByConsequence } from "../src/screens/failover/ChecksByConsequence";
import { formatStamp } from "../src/shell/time";

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

it("the detail's grouped checks offer it under the failed Configuration sync row of a Palo Alto unit, when the server offers it", () => {
  const row = (canRun: boolean) => {
    const checks = [4, 5].flatMap(no => ids.map((id, i) => ({
      checkNo: no, title: no === 4 ? "Configuration sync" : "Session synchronization", member: `Member ${i + 1}`, device_id: id,
      result: (no === 4 ? "FAIL" : "PASS") as "FAIL" | "PASS", status: (no === 4 ? "FAIL" : "PASS") as "FAIL" | "PASS", summary: "s", blocking: true, derived: {} })));
    return { unit: { ...unit({ canRun }), readiness: { status: "NOT_READY" as const, observedAt: at, failedCheck: "Configuration sync", checks } }, checks };
  };
  for (const [canRun, expected] of [[true, 1], [false, 0]] as const) {
    const { unit: u, checks } = row(canRun);
    const { unmount } = render(<ChecksByConsequence checks={checks} members={members} vendor="palo_alto"
      rowAction={checkNo => checkNo === 4 ? <PanConfigSyncAction unit={u} members={members} /> : null} />);
    const entry = document.querySelector("[data-check-entry='4']") as HTMLElement;
    expect(within(entry).queryAllByRole("button", { name: "Sync configuration to passive" })).toHaveLength(expected);
    expect(document.querySelector("[data-check-entry='5']")).toBeNull();
    unmount();
  }
});

it("shows the settling message with the request time during the 60 s wait and when follow-up readiness sees synchronization in progress, and only shows still fails for a non-in-progress FAIL", async () => {
  const preSyncAt = "2026-11-20T10:00:00Z";
  const requestedAt = "2026-11-20T10:05:00Z";
  const finishedAt = "2026-11-20T10:06:00Z";
  const followUpAt = "2026-11-20T10:06:15Z";
  const expectedSettling = `Sync requested -- the passive member applies it; readiness re-runs in about a minute (${formatStamp(requestedAt)})`;
  expect(configSyncSettlingText(requestedAt)).toBe(expectedSettling);

  // 1. During the 60 s settle window (deviceStatus is success while the worker waits, or DONE while follow-up readiness runs)
  // and when the follow-up readiness finishes with "synchronization in progress":
  api.request.mockResolvedValue(record("QUEUED", { requestedAt }));
  api.get.mockResolvedValueOnce(record("SYNCING", { requestedAt, deviceStatus: "success", mutationPossible: true }))
    .mockResolvedValue(record("DONE", { requestedAt, finishedAt, outcome: "CONFIG_SYNC_REQUESTED", deviceStatus: "success",
      mutationPossible: true, readinessRunId: "run-in-progress" }));
  api.run.mockResolvedValue({
    runId: "run-in-progress",
    state: "DONE",
    checks: [
      { checkNo: 4, phase: "pre", status: "FAIL", derived: JSON.stringify({ first: { "running-sync": "synchronization in progress" } }) },
      { checkNo: 4, phase: "pre", status: "FAIL", derived: JSON.stringify({ second: { "running-sync": "synchronization in progress" } }) },
    ],
  });
  const done = vi.fn();
  const { unmount } = render(<PanConfigSyncAction unit={unit({ readinessObservedAt: preSyncAt })} onDone={done} pollMs={0} />);
  fireEvent.click(screen.getByRole("button", { name: "Sync configuration to passive" }));
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Sync configuration" }));
  await waitFor(() => expect(done).toHaveBeenCalled());
  expect(screen.getByText(expectedSettling)).toBeInTheDocument();
  expect(screen.queryByText(/Configuration sync still fails/)).toBeNull();
  unmount();

  // 2. Only when the follow-up readiness finishes with a FAIL that is not "in progress" does it show "Configuration sync still fails":
  api.request.mockResolvedValue(record("QUEUED", { requestedAt }));
  api.get.mockResolvedValue(record("DONE", { requestedAt, finishedAt, outcome: "CONFIG_SYNC_REQUESTED", deviceStatus: "success",
    mutationPossible: true, readinessRunId: "run-failed" }));
  api.run.mockResolvedValue({
    runId: "run-failed",
    state: "DONE",
    checks: [
      { checkNo: 4, phase: "pre", status: "FAIL", derived: JSON.stringify({ first: { "running-sync": "not synchronized" } }) },
      { checkNo: 4, phase: "pre", status: "FAIL", derived: JSON.stringify({ second: { "running-sync": "not synchronized" } }) },
    ],
  });
  const doneFailed = vi.fn();
  const second = render(<PanConfigSyncAction unit={unit({ readinessObservedAt: followUpAt })} onDone={doneFailed} pollMs={0} />);
  fireEvent.click(screen.getByRole("button", { name: "Sync configuration to passive" }));
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Sync configuration" }));
  await waitFor(() => expect(doneFailed).toHaveBeenCalled());
  expect(screen.getByText("Pre-checks finished: Configuration sync still fails.")).toBeInTheDocument();
  expect(screen.queryByText(expectedSettling)).toBeNull();
  second.unmount();
});

it("the HA readiness row shows the settling message with the request time during the wait and when follow-up readiness sees synchronization in progress", () => {
  const preSyncAt = "2026-11-20T10:00:00Z";
  const requestedAt = "2026-11-20T10:05:00Z";
  const finishedAt = "2026-11-20T10:06:00Z";
  const followUpAt = "2026-11-20T10:06:15Z";
  const expectedSettling = `Sync requested -- the passive member applies it; readiness re-runs in about a minute (${formatStamp(requestedAt)})`;
  const cluster = { ref: "CLS-BRAVO-02", title: "CLS-BRAVO-02",
    members: members.map(m => ({ ...m, vendor_hint: "palo_alto", inventory_collected_at: preSyncAt })) as unknown as DeviceSummary[] };
  const makeRow = (observedAt: string, latest: PanConfigSyncRecord | null, runningSync: string,
      extraFacts: Partial<PanConfigSyncFacts> = {}): CpFailoverSummary => ({
    clusterId: "opaque-cluster", unitId: "opaque-cluster", cluster_member_ref: cluster.ref,
    vendor: "palo_alto", activeWindow: false, lastRunAt: null, lastRunState: null, lastRunOutcome: null,
    masked: false, canRunReadiness: true, refusalReason: "CHECK_NOT_READY", readinessObservedAt: observedAt,
    members, configSync: facts({ readinessObservedAt: observedAt, latest, ...extraFacts }),
    readiness: {
      status: "NOT_READY", observedAt, failedCheck: "Configuration sync",
      checks: ids.map((id, i) => ({
        checkNo: 4, title: "Configuration sync", member: `Member ${i + 1}`, device_id: id,
        result: "FAIL" as const, status: "FAIL" as const, summary: "Configuration not synchronized", blocking: true,
        derived: { first: { "running-sync": runningSync }, second: { "running-sync": runningSync } },
      })),
    },
  });
  const props = { clusters: [cluster], running: null, busy: false, progress: {}, error: null,
    onOpen: vi.fn(), onRun: vi.fn(), onBulkRun: vi.fn() };
  const doneSync = record("DONE", { requestedAt, finishedAt, outcome: "CONFIG_SYNC_REQUESTED", deviceStatus: "success",
    mutationPossible: true, readinessRunId: "run-followup" });

  // 1. During the window before follow-up readiness finishes (observedAt is still preSyncAt):
  const { rerender } = render(<HaReadinessList {...props} rows={{ [cluster.ref]: [makeRow(preSyncAt, doneSync, "not synchronized")] }} />);
  const haRow = screen.getByRole("row", { name: "CLS-BRAVO-02" });
  expect(within(haRow).getByText(expectedSettling)).toBeInTheDocument();
  expect(screen.queryByText(/Configuration sync still fails/)).toBeNull();

  // 2. Follow-up readiness finished (followUpAt >= finishedAt), and still sees "synchronization in progress":
  rerender(<HaReadinessList {...props} rows={{ [cluster.ref]: [makeRow(followUpAt, doneSync, "synchronization in progress")] }} />);
  expect(within(screen.getByRole("row", { name: "CLS-BRAVO-02" })).getByText(expectedSettling)).toBeInTheDocument();
  expect(screen.queryByText(/Configuration sync still fails/)).toBeNull();

  // 3. Follow-up readiness finished (followUpAt >= finishedAt) with a FAIL that is not "in progress":
  rerender(<HaReadinessList {...props} rows={{ [cluster.ref]: [makeRow(followUpAt, doneSync, "not synchronized")] }} />);
  expect(within(screen.getByRole("row", { name: "CLS-BRAVO-02" })).getByText("Configuration sync still fails")).toBeInTheDocument();
  expect(screen.queryByText(expectedSettling)).toBeNull();
});
