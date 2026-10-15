import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { SanitizerScreen, sanitizerBytes, sanitizerError } from "../src/screens/SanitizerScreen";
import * as api from "../src/auth/adminApi";
import type { SanitizeJob, SanitizerInfo } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async importOriginal => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  getSanitizerInfo: vi.fn(), getSanitizeJob: vi.fn(), getRevertJob: vi.fn(), uploadForSanitizer: vi.fn(),
}));

const JOB = "11111111-2222-4333-8444-555555555555";
const REVERT = "66666666-7777-4888-9999-aaaaaaaaaaaa";
const limits = { max_file_bytes: 2 * 1024 ** 3, vault_retention_days: 90, output_ttl_minutes: 60, vault_available: true };
function info(permissions = { run: true, revert: true, revert_any_job: false }): SanitizerInfo {
  return { permissions, limits };
}
function job(state: "RUNNING" | "SUCCEEDED" | "FAILED", extra: Partial<SanitizeJob> = {}): SanitizeJob {
  return { job_id: JOB, state, file_type: state === "SUCCEEDED" ? "csv" : null, created_at: "2026-10-16T08:00:00Z",
    finished_at: state === "RUNNING" ? null : "2026-10-16T08:00:05Z", input_bytes: 2048, output_bytes: state === "SUCCEEDED" ? 2100 : null,
    counts: state === "SUCCEEDED" ? { ipv4: 3, hostname: 2 } : {}, failure_reason: state === "FAILED" ? "UNSUPPORTED_BINARY" : null,
    output_state: state === "SUCCEEDED" ? "READY" : "PENDING", vault_entries: 5, vault_expires_at: "2027-01-14T08:00:00Z",
    vault_pruned: false, ...extra };
}
function themed() { return render(<ThemeProvider theme={m3Theme}><SanitizerScreen /></ThemeProvider>); }
function drop(target: HTMLElement, file: File) {
  fireEvent.drop(target, { dataTransfer: { files: [file] } });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getSanitizerInfo).mockResolvedValue(info());
});

describe("Data Sanitizer screen", () => {
  it("states the limits, the temporary-file rule and the vault retention", async () => {
    themed();
    expect(await screen.findByText(/At most 2.0 GB per file/)).toBeInTheDocument();
    expect(screen.getByText(/after one download or 60 minutes/)).toBeInTheDocument();
    expect(screen.getByText(/for 90 days, encrypted/)).toBeInTheDocument();
    expect(screen.getByText(/Veri Anonimleştirme/)).toBeInTheDocument();
  });

  it("sanitizes a dropped file with progress, follows the job and offers one download", async () => {
    vi.mocked(api.uploadForSanitizer).mockImplementation(async (_route, _file, _fields, onProgress) => {
      onProgress(1024, 2048);
      return { job_id: JOB, state: "RUNNING" };
    });
    vi.mocked(api.getSanitizeJob).mockResolvedValueOnce(job("SUCCEEDED"));
    themed();
    const zone = await screen.findByRole("button", { name: "Drop a file here or choose one" });
    drop(zone, new File(["a,b\n192.0.2.1,fw\n"], "synthetic.csv", { type: "text/csv" }));
    await waitFor(() => expect(api.uploadForSanitizer).toHaveBeenCalledWith("sanitize", expect.any(File), {}, expect.any(Function)));
    const result = await screen.findByLabelText("Sanitizer result");
    const counts = within(result).getByRole("table", { name: "Counts per kind" });
    expect(within(counts).getByText("IPv4 addresses")).toBeInTheDocument();
    expect(within(counts).getByText("3")).toBeInTheDocument();
    const download = within(result).getByRole("link", { name: "Download" });
    expect(download).toHaveAttribute("href", `/api/v2/sanitize/jobs/${JOB}/download`);
    download.addEventListener("click", event => event.preventDefault()); // jsdom has no navigation
    fireEvent.click(download);
    expect(await within(result).findByText(/neXus kept no copy/)).toBeInTheDocument();
  });

  it("reverts against one named job and shows the failure reason a job ended with", async () => {
    vi.mocked(api.uploadForSanitizer).mockResolvedValue({ revert_id: REVERT, state: "RUNNING" });
    vi.mocked(api.getRevertJob).mockResolvedValue({ revert_id: REVERT, state: "FAILED", scope: "ONE_JOB", source_job_id: JOB,
      file_type: null, created_at: "2026-10-16T08:00:00Z", finished_at: "2026-10-16T08:00:01Z", input_bytes: 10, output_bytes: null,
      counts: {}, failure_reason: "UNSUPPORTED_BINARY", output_state: "NONE" });
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Revert" }));
    fireEvent.change(screen.getByLabelText("Sanitize job id (optional)"), { target: { value: JOB } });
    expect(screen.queryByLabelText("Use every vault, not only my jobs")).not.toBeInTheDocument();
    drop(screen.getByRole("button", { name: "Drop a file here or choose one" }), new File(["x"], "back.bin"));
    await waitFor(() => expect(api.uploadForSanitizer).toHaveBeenCalledWith("revert", expect.any(File), { source_job_id: JOB }, expect.any(Function)));
    expect(await screen.findByText(/The job failed: UNSUPPORTED_BINARY/)).toBeInTheDocument();
  });

  it("offers every vault only when the server says the session may use it", async () => {
    vi.mocked(api.getSanitizerInfo).mockResolvedValue(info({ run: true, revert: true, revert_any_job: true }));
    vi.mocked(api.uploadForSanitizer).mockResolvedValue({ revert_id: REVERT, state: "RUNNING" });
    vi.mocked(api.getRevertJob).mockResolvedValue({ revert_id: REVERT, state: "RUNNING", scope: "ALL_JOBS", source_job_id: null,
      file_type: null, created_at: "2026-10-16T08:00:00Z", finished_at: null, input_bytes: 10, output_bytes: null, counts: {},
      failure_reason: null, output_state: "PENDING" });
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Revert" }));
    fireEvent.click(screen.getByLabelText("Use every vault, not only my jobs"));
    drop(screen.getByRole("button", { name: "Drop a file here or choose one" }), new File(["x"], "back.txt"));
    await waitFor(() => expect(api.uploadForSanitizer).toHaveBeenCalledWith("revert", expect.any(File), { scope: "all" }, expect.any(Function)));
    expect(await screen.findByRole("status", { name: "Processing" })).toBeInTheDocument();
  });

  it("refuses an oversize file before uploading and explains a refusal in the server's words", async () => {
    themed();
    const zone = await screen.findByRole("button", { name: "Drop a file here or choose one" });
    const big = new File(["x"], "big.pcap");
    Object.defineProperty(big, "size", { value: 3 * 1024 ** 3 });
    drop(zone, big);
    expect(await screen.findByText("A file may be at most 2.0 GB.")).toBeInTheDocument();
    expect(api.uploadForSanitizer).not.toHaveBeenCalled();
    expect(sanitizerError({ status: 413, body: { error: "FILE_TOO_LARGE", reason: "a file may be at most 2147483648 bytes" } }, "x"))
      .toBe("a file may be at most 2147483648 bytes");
    expect(sanitizerError({ status: 0, body: {} }, "x")).toMatch(/did not complete/);
    expect(sanitizerBytes(1536)).toBe("1.5 KB");
  });

  it("says so when the vault is unavailable or the role allows nothing", async () => {
    vi.mocked(api.getSanitizerInfo).mockResolvedValue({ permissions: { run: false, revert: false, revert_any_job: false },
      limits: { ...limits, vault_available: false } });
    themed();
    expect(await screen.findByText(/mapping vault is not available/)).toBeInTheDocument();
    expect(screen.getByText("Your role does not allow this action.")).toBeInTheDocument();
  });
});
