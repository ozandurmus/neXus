import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BackupAttempts } from "../src/screens/BackupAttempts";
import { BackupScreen } from "../src/screens/BackupScreen";
import { BackupPanel } from "../src/screens/InventoryPanels";
import type { JobEventView } from "../src/auth/adminApi";

const attempt = (state: string, hasTranscript: boolean): JobEventView => ({
  job_id: `job-synthetic-${state}`, job_type: "backup", target_device_id: "device-synthetic",
  submitted_at: "2026-10-02T08:00:00Z", state, has_transcript: hasTranscript,
  terminal_reason: state === "FAILED" ? "Synthetic collection failure" : undefined,
});

function stubJobs(items: JobEventView[], total = items.length) {
  const fetch = vi.fn((input: string) => {
    const url = String(input);
    const body = url.startsWith("/api/v2/jobs") ? { items, total, page: 1, page_size: 25 }
      : url.endsWith("/transcript") ? [{ seq: 1, at: "2026-10-02T08:00:00Z", elapsedMs: 1, channel: "ssh", kind: "response", text: "Synthetic transcript" }]
      : url.endsWith("/backups") || url === "/backups" ? { backups: [], baselines: {} }
      : url === "/devices" ? { devices: [{ device_id: "device-synthetic", hostname: "FW-TANGO-04", role: "gateway", enrollment_state: "ENROLLED", backup_target: true }] }
      : {};
    return Promise.resolve(new Response(JSON.stringify(body)));
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

afterEach(() => vi.unstubAllGlobals());

describe("backup attempt transcript entry points", () => {
  it.each(["backup_admin", "security_admin", "aiview", "viewer"])("uses the Jobs affordance for %s across success and failure", async (role) => {
    // Jobs resolves transcript permissions on the server; clients never infer access from role tokens.
    const allowed = role === "backup_admin" || role === "security_admin";
    stubJobs([attempt("COMPLETED", allowed), attempt("FAILED", allowed)]);
    render(<BackupAttempts deviceId="device-synthetic" />);
    await screen.findByText("COMPLETED");
    expect(screen.queryAllByRole("button", { name: "Transcript" })).toHaveLength(allowed ? 2 : 0);
    const failure = screen.getByText("FAILED: Synthetic collection failure").parentElement!;
    expect(within(failure).queryByRole("button", { name: "Transcript" }) !== null).toBe(allowed);
  });

  it("opens the existing viewer using the exact failed job id", async () => {
    const fetch = stubJobs([attempt("FAILED", true)]);
    render(<BackupAttempts deviceId="device-synthetic" />);
    fireEvent.click(await screen.findByRole("button", { name: "Transcript" }));
    expect(await screen.findByText("Synthetic transcript")).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith("/jobs/job-synthetic-FAILED/transcript", expect.anything());
  });

  it("paginates attempt history without truncating older transcripts", async () => {
    const fetch = stubJobs([attempt("COMPLETED", true)], 26);
    render(<BackupAttempts deviceId="device-synthetic" />);
    fireEvent.click(await screen.findByRole("button", { name: "Go to page 2" }));
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url).includes("page=2"))).toBe(true));
  });

  it.each([
    ["COMPLETED", true], ["FAILED", true], ["COMPLETED", false], ["FAILED", false],
  ])("shows device and History affordances for %s (access %s), even without an archive", async (state, allowed) => {
    stubJobs([attempt(state, allowed)]);
    render(<BackupScreen />);
    const row = (await screen.findByText("FW-TANGO-04")).closest("tr")!;
    await within(row).findByText(state === "FAILED" ? "FAILED: Synthetic collection failure" : state);
    expect(within(row).queryAllByRole("button", { name: "Transcript" })).toHaveLength(allowed ? 1 : 0);
    fireEvent.click(within(row).getByRole("button", { name: "History" }));
    const dialog = await screen.findByRole("dialog");
    await within(dialog).findByText(state === "FAILED" ? "FAILED: Synthetic collection failure" : state);
    expect(within(dialog).queryAllByRole("button", { name: "Transcript" })).toHaveLength(allowed ? 1 : 0);
  });

  it.each([true, false])("Inventory uses the server affordance (%s) for both outcomes", async (allowed) => {
    stubJobs([attempt("COMPLETED", allowed), attempt("FAILED", allowed)]);
    render(<BackupPanel deviceId="device-synthetic" />);
    await screen.findByText("COMPLETED");
    expect(screen.queryAllByRole("button", { name: "Transcript" })).toHaveLength(allowed ? 2 : 0);
  });

  it("reports a failed attempt-list read without inventing an empty history", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("{}", { status: 500 })));
    render(<BackupAttempts deviceId="device-synthetic" />);
    expect(await screen.findByText("Backup attempts could not be loaded.")).toBeInTheDocument();
  });
});
