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
    await screen.findByText("Completed");
    expect(screen.queryAllByRole("button", { name: "Transcript" })).toHaveLength(allowed ? 2 : 0);
    expect(screen.queryAllByRole("button", { name: "Download" })).toHaveLength(allowed ? 2 : 0);
    const failure = screen.getByText("Failed · Synthetic collection failure").parentElement!;
    expect(within(failure).queryByRole("button", { name: "Transcript" }) !== null).toBe(allowed);
    expect(within(failure).queryByRole("button", { name: "Download" }) !== null).toBe(allowed);
  });

  it("opens the existing viewer using the exact failed job id", async () => {
    const fetch = stubJobs([attempt("FAILED", true)]);
    render(<BackupAttempts deviceId="device-synthetic" />);
    fireEvent.click(await screen.findByRole("button", { name: "Transcript" }));
    expect(await screen.findByText("Synthetic transcript")).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith("/jobs/job-synthetic-FAILED/transcript", expect.anything());
  });

  it("downloads the failed attempt's own transcript as a text file named for that job, without opening the viewer", async () => {
    const fetch = stubJobs([attempt("COMPLETED", true), attempt("FAILED", true)]);
    const names: string[] = [];
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn(() => "blob:transcript") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) { names.push(this.download); });
    render(<BackupAttempts deviceId="device-synthetic" />);
    const failure = (await screen.findByText("Failed · Synthetic collection failure")).parentElement!;
    fireEvent.click(within(failure).getByRole("button", { name: "Download" }));
    // 08:00Z is 11:00 in Turkey; the short id is the first eight characters of the job id.
    await waitFor(() => expect(names).toEqual(["backup-20261002-1100-job-synt.txt"]));
    expect(fetch).toHaveBeenCalledWith("/jobs/job-synthetic-FAILED/transcript", expect.anything());
    expect(fetch).not.toHaveBeenCalledWith("/jobs/job-synthetic-COMPLETED/transcript", expect.anything());
    expect(screen.queryByLabelText("Search transcript")).toBeNull();
    vi.restoreAllMocks();
  });

  it("paginates attempt history without truncating older transcripts", async () => {
    const fetch = stubJobs([attempt("COMPLETED", true)], 26);
    render(<BackupAttempts deviceId="device-synthetic" />);
    // the one pagination footer (post-release review 2026-10-20, N15): the range words and the chevrons
    expect(await screen.findByText("1–25 of 26 attempts")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url).includes("page=2"))).toBe(true));
  });

  it.each([
    ["COMPLETED", true], ["FAILED", true], ["COMPLETED", false], ["FAILED", false],
  ])("shows device and History affordances for %s (access %s), even without an archive", async (state, allowed) => {
    stubJobs([attempt(state, allowed)]);
    render(<BackupScreen />);
    const row = (await screen.findByText("FW-TANGO-04")).closest("tr")!;
    const word = state === "FAILED" ? "Failed · Synthetic collection failure" : "Completed";
    await within(row).findByText(word);
    expect(within(row).queryAllByRole("button", { name: "Transcript" })).toHaveLength(allowed ? 1 : 0);
    expect(within(row).queryAllByRole("button", { name: "Download" })).toHaveLength(allowed ? 1 : 0);
    // The row actions live in the row's menu (UI review 2026-10-18, F-4); History is always reachable, even without an archive.
    fireEvent.click(within(row).getByRole("button", { name: "Actions for FW-TANGO-04" }));
    fireEvent.click(await screen.findByRole("menuitem", { name: "History" }));
    const dialog = await screen.findByRole("dialog");
    await within(dialog).findByText(word);
    expect(within(dialog).queryAllByRole("button", { name: "Transcript" })).toHaveLength(allowed ? 1 : 0);
    expect(within(dialog).queryAllByRole("button", { name: "Download" })).toHaveLength(allowed ? 1 : 0);
  });

  it.each([true, false])("Inventory uses the server affordance (%s) for both outcomes", async (allowed) => {
    stubJobs([attempt("COMPLETED", allowed), attempt("FAILED", allowed)]);
    render(<BackupPanel deviceId="device-synthetic" />);
    await screen.findByText("Completed");
    expect(screen.queryAllByRole("button", { name: "Transcript" })).toHaveLength(allowed ? 2 : 0);
  });

  it("reports a failed attempt-list read without inventing an empty history", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("{}", { status: 500 })));
    render(<BackupAttempts deviceId="device-synthetic" />);
    expect(await screen.findByText("Backup attempts could not be loaded.")).toBeInTheDocument();
  });

  it("fetches latest backup attempts for all fleet rows in one batched /api/v2/jobs call", async () => {
    const devices = [
      { device_id: "d1", hostname: "FW-TANGO-04", role: "gateway", enrollment_state: "ENROLLED", backup_target: true },
      { device_id: "d2", hostname: "FW-ROMEO-01", role: "gateway", enrollment_state: "ENROLLED", backup_target: true },
      { device_id: "d3", hostname: "FW-BRAVO-02", role: "gateway", enrollment_state: "ENROLLED", backup_target: true },
    ];
    const items: JobEventView[] = [
      { job_id: "job-d1", job_type: "cp_gateway_backup", target_device_id: "d1", submitted_at: "2026-10-02T08:00:00Z", state: "COMPLETED", has_transcript: true },
      { job_id: "job-d2", job_type: "pan_device_state_backup", target_device_id: "d2", submitted_at: "2026-10-02T08:05:00Z", state: "FAILED", terminal_reason: "Synthetic collection failure", has_transcript: false },
    ];
    const fetch = vi.fn((input: string) => {
      const url = String(input);
      const body = url.startsWith("/api/v2/jobs") ? { items, total: items.length, page: 1, page_size: 500 }
        : url.endsWith("/backups") || url === "/backups" ? { backups: [], baselines: {} }
        : url === "/devices" ? { devices }
        : {};
      return Promise.resolve(new Response(JSON.stringify(body)));
    });
    vi.stubGlobal("fetch", fetch);

    render(<BackupScreen />);
    const row1 = (await screen.findByText("FW-TANGO-04")).closest("tr")!;
    const row2 = screen.getByText("FW-ROMEO-01").closest("tr")!;
    const row3 = screen.getByText("FW-BRAVO-02").closest("tr")!;
    await within(row1).findByText("Completed");
    await within(row2).findByText("Failed · Synthetic collection failure");
    await within(row3).findByText("No attempt");

    const jobCalls = fetch.mock.calls.map(([u]) => String(u)).filter((u) => u.startsWith("/api/v2/jobs"));
    expect(jobCalls).toEqual(["/api/v2/jobs?job_type=backup&latest_per_device=true"]);
  });
  it("marks a device the bounded batch left out as unknown, never as having no attempt", async () => {
    const devices = [
      { device_id: "d1", hostname: "FW-TANGO-04", role: "gateway", enrollment_state: "ENROLLED", backup_target: true },
      { device_id: "d2", hostname: "FW-ROMEO-01", role: "gateway", enrollment_state: "ENROLLED", backup_target: true },
    ];
    const items: JobEventView[] = [
      { job_id: "job-d1", job_type: "cp_gateway_backup", target_device_id: "d1", submitted_at: "2026-10-02T08:00:00Z", state: "COMPLETED", has_transcript: true },
    ];
    // a full page (items == page_size): the server may have cut devices off
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      const url = String(input);
      const body = url.startsWith("/api/v2/jobs") ? { items, total: 1, page: 1, page_size: 1 }
        : url.endsWith("/backups") || url === "/backups" ? { backups: [], baselines: {} }
        : url === "/devices" ? { devices }
        : {};
      return Promise.resolve(new Response(JSON.stringify(body)));
    }));
    render(<BackupScreen />);
    const row2 = (await screen.findByText("FW-ROMEO-01")).closest("tr")!;
    await within(row2).findByText("Backup attempts could not be loaded.");
    expect(within(row2).queryByText("No attempt")).toBeNull();
  });

  it("renders credential_unresolvable with the missing slot and Administration > Credentials link, and PAN partial as a warning when the manifest is complete", async () => {
    const devices = [
      { device_id: "d-spark", hostname: "FW-TANGO-04", role: "gateway", vendor_hint: "check_point", enrollment_state: "ENROLLED", backup_target: true },
      { device_id: "d-pan-ok", hostname: "MGR-ROMEO-01", role: "management_server", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", backup_target: true },
      { device_id: "d-pan-no-arch", hostname: "MGR-BRAVO-02", role: "management_server", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", backup_target: true },
    ];
    const backups = [
      { artefact_id: "art-pan-1", device_id: "d-pan-ok", collected_at: "2026-11-20T02:05:00Z", size_bytes: 2097152, digest_prefix: "1234abcd", validation_level: "V1", deviation_state: "changed", vendor: "palo_alto" },
    ];
    const items: JobEventView[] = [
      { job_id: "job-spark", job_type: "cp_spark_sftp_backup", target_device_id: "d-spark", submitted_at: "2026-11-20T02:01:00Z", state: "FAILED", terminal_reason: "credential_unresolvable: Spark backup or receiver credential is missing", has_transcript: false },
      { job_id: "job-pan-ok", job_type: "pan_device_state_backup", target_device_id: "d-pan-ok", submitted_at: "2026-11-20T02:05:00Z", state: "FAILED", terminal_reason: "partial: stored without running-config.set (show config running: TimedOut)", has_transcript: false },
      { job_id: "job-pan-missing", job_type: "pan_device_state_backup", target_device_id: "d-pan-no-arch", submitted_at: "2026-11-20T02:06:00Z", state: "FAILED", terminal_reason: "partial: stored without running-config.set (show config running: TimedOut)", has_transcript: false },
    ];
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      const url = String(input);
      const body = url.startsWith("/api/v2/jobs") ? { items, total: items.length, page: 1, page_size: 500 }
        : url.endsWith("/backups") || url === "/backups" ? { backups, baselines: {} }
        : url === "/devices" ? { devices }
        : {};
      return Promise.resolve(new Response(JSON.stringify(body)));
    }));

    render(<BackupScreen />);
    const sparkRow = (await screen.findByText("FW-TANGO-04")).closest("tr")!;
    const panOkRow = screen.getByText("MGR-ROMEO-01").closest("tr")!;
    const panNoArchRow = screen.getByText("MGR-BRAVO-02").closest("tr")!;

    await within(sparkRow).findByText("Failed · Spark backup or backup receiver credential (backup_receiver) is missing");
    const credLink = within(sparkRow).getByRole("link", { name: "Administration > Credentials" });
    expect(credLink).toHaveAttribute("href", "?screen=administration&tab=registry&section=credentials");

    await within(panOkRow).findByText("Partial · stored without running config (timeout)");
    expect(within(panOkRow).queryByText(/^Failed/)).toBeNull();

    await within(panNoArchRow).findByText("Failed · stored without running config (timeout)");
  });
});
