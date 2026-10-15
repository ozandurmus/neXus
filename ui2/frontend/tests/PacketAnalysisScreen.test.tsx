import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PacketAnalysisScreen, packetBytes, packetError } from "../src/screens/PacketAnalysisScreen";
import * as api from "../src/auth/adminApi";
import type { PacketCaseDetail, PacketCaseList } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async importOriginal => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  listPacketCases: vi.fn(), getPacketCase: vi.fn(), createPacketCase: vi.fn(), analyzePacketCase: vi.fn(),
  getPacketReport: vi.fn(), deletePacketCase: vi.fn(), uploadPacketCapture: vi.fn(),
}));

const CASE = "11111111-2222-4333-8444-555555555555";
const JOB = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
const stages = ["ingest", "core_flow_loss"].map(stage => ({ stage, verdict: "unknown", reason: "engine not yet ported" }));
const summary = { verdict: "unknown", reason: "engine not yet ported", stages, capture_count: 1 };
const operator = { run: true, raw_view: false, masked: false };
const aiview = { run: false, raw_view: false, masked: true };

function listing(permissions = operator): PacketCaseList {
  return {
    cases: [{ case_id: CASE, title: permissions.masked ? "OBJ-TANGO-04" : "Synthetic loss case", created_at: "2026-10-15T08:00:00Z",
      expires_at: "2026-10-22T08:00:00Z", state: "ANALYZED", total_bytes: 3 * 1024 * 1024, capture_count: 1,
      payload_zeroing: false, latest_job_id: JOB }],
    permissions, limits: { max_file_bytes: 2 * 1024 ** 3, max_store_bytes: 100 * 1024 ** 3, used_bytes: 3 * 1024 * 1024, retention_days: 7 },
  };
}
function detail(permissions = operator): PacketCaseDetail {
  return { ...listing(permissions).cases[0], permissions,
    captures: [{ capture_id: "66666666-7777-4888-9999-aaaaaaaaaaaa", display_name: permissions.masked ? "OBJ-JULIET-06" : "synthetic-a.pcap",
      format: "pcap", size_bytes: 3 * 1024 * 1024, uploaded_at: "2026-10-15T08:05:00Z", ...(permissions.masked ? {} : { sha256: "a".repeat(64) }) }],
    runs: [{ job_id: JOB, case_id: CASE, state: "COMPLETED", requested_at: "2026-10-15T08:06:00Z", started_at: "2026-10-15T08:06:05Z",
      finished_at: "2026-10-15T08:06:09Z", engine_version: "stub-0", failure_reason: null, summary }] };
}
function themed() { return render(<ThemeProvider theme={m3Theme}><PacketAnalysisScreen /></ThemeProvider>); }

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.listPacketCases).mockResolvedValue(listing());
  vi.mocked(api.getPacketCase).mockResolvedValue(detail());
});

describe("Packet Analysis screen", () => {
  it("lists cases with their expiry date and the store limits", async () => {
    themed();
    const table = await screen.findByRole("table", { name: "Packet analysis cases" });
    expect(within(table).getByText("Synthetic loss case")).toBeInTheDocument();
    expect(within(table).getByText("2026-10-22")).toBeInTheDocument();
    expect(within(table).getByText("ANALYZED")).toBeInTheDocument();
    expect(screen.getByText(/at most 2.0 GB per capture/)).toBeInTheDocument();
    expect(screen.getByText(/Every stage reports unknown/)).toBeInTheDocument();
  });

  it("creates a case with the payload zeroing option an operator chose", async () => {
    vi.mocked(api.createPacketCase).mockResolvedValue({ ...listing().cases[0], case_id: CASE, state: "OPEN" });
    themed();
    fireEvent.change(await screen.findByLabelText("Case title"), { target: { value: "  New synthetic case " } });
    fireEvent.click(screen.getByLabelText(/Payload zeroing/));
    fireEvent.click(screen.getByRole("button", { name: "Create case" }));
    await waitFor(() => expect(api.createPacketCase).toHaveBeenCalledWith("New synthetic case", true));
  });

  it("uploads with progress, analyses and shows verdict, unknown reasons and the JSON", async () => {
    let finish: () => void = () => undefined;
    vi.mocked(api.uploadPacketCapture).mockImplementation((_id, _file, onProgress) => new Promise(resolve => {
      onProgress(512, 1024);
      finish = () => resolve({ capture_id: "c", size_bytes: 1024, format: "pcap" });
    }));
    vi.mocked(api.analyzePacketCase).mockResolvedValue({ job_id: JOB, state: "QUEUED" });
    vi.mocked(api.getPacketReport).mockResolvedValue({ job_id: JOB, case_id: CASE, projection: "full",
      report: { engine: "stub", ...summary } });
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    const card = await screen.findByLabelText("Case detail");
    expect(within(card).getByText("synthetic-a.pcap")).toBeInTheDocument();
    const file = new File([new Uint8Array(1024)], "synthetic-b.pcapng");
    fireEvent.change(screen.getByLabelText("Capture file"), { target: { files: [file] } });
    const progress = await screen.findByLabelText("Upload progress");
    expect(within(progress).getByText("Uploading synthetic-b.pcapng: 512 B of 1.0 KB")).toBeInTheDocument();
    expect(within(progress).getByRole("progressbar")).toHaveAttribute("aria-valuenow", "50");
    finish();
    expect(await screen.findByText("Capture uploaded.")).toBeInTheDocument();
    expect(api.uploadPacketCapture).toHaveBeenCalledWith(CASE, file, expect.any(Function));

    fireEvent.click(screen.getByRole("button", { name: "Analyze" }));
    await waitFor(() => expect(api.analyzePacketCase).toHaveBeenCalledWith(CASE));
    fireEvent.click(screen.getByRole("button", { name: "View result" }));
    const result = await screen.findByLabelText("Analysis result");
    expect(within(result).getByText("Verdict: unknown")).toBeInTheDocument();
    expect(within(result).getAllByText("engine not yet ported").length).toBeGreaterThan(1);
    expect(within(result).getByText("core_flow_loss")).toBeInTheDocument();
    expect(within(result).getByLabelText("Result JSON").textContent).toContain('"engine": "stub"');
  });

  it("refuses a file over the server's per-file limit before sending a byte", async () => {
    vi.mocked(api.listPacketCases).mockResolvedValue({ ...listing(), limits: { ...listing().limits, max_file_bytes: 100 } });
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    await screen.findByLabelText("Case detail");
    fireEvent.change(screen.getByLabelText("Capture file"), { target: { files: [new File([new Uint8Array(200)], "big.pcap")] } });
    expect(await screen.findByText("A capture may be at most 100 B.")).toBeInTheDocument();
    expect(api.uploadPacketCapture).not.toHaveBeenCalled();
  });

  it("renders the masked aiview projection with no write or raw affordance", async () => {
    vi.mocked(api.listPacketCases).mockResolvedValue(listing(aiview));
    vi.mocked(api.getPacketCase).mockResolvedValue(detail(aiview));
    vi.mocked(api.getPacketReport).mockResolvedValue({ job_id: JOB, case_id: CASE, projection: "masked", summary });
    themed();
    expect(await screen.findByText("Masked view")).toBeInTheDocument();
    expect(screen.queryByLabelText("Case title")).toBeNull();
    fireEvent.click(await screen.findByRole("button", { name: "OBJ-TANGO-04" }));
    const card = await screen.findByLabelText("Case detail");
    expect(within(card).getByText("OBJ-JULIET-06")).toBeInTheDocument();
    expect(within(card).queryByText(/sha256/)).toBeNull();
    expect(screen.queryByRole("button", { name: "Upload capture" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Analyze" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Delete case" })).toBeNull();
    expect(screen.queryByRole("link", { name: "Download" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "View result" }));
    expect(await screen.findByText("Masked summary")).toBeInTheDocument();
  });

  it("offers the ledgered download only when the server grants raw view", async () => {
    const admin = { run: true, raw_view: true, masked: false };
    vi.mocked(api.listPacketCases).mockResolvedValue(listing(admin));
    vi.mocked(api.getPacketCase).mockResolvedValue(detail(admin));
    themed();
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    const link = await screen.findByRole("link", { name: "Download" });
    expect(link).toHaveAttribute("href", "/api/v2/packet-analysis/captures/66666666-7777-4888-9999-aaaaaaaaaaaa/download");
  });

  it("shows the server's refusal reason and never a guess", () => {
    expect(packetError({ status: 507, body: { error: "STORE_FULL", reason: "The analysis store would pass its cap" } }, "x"))
      .toBe("The analysis store would pass its cap");
    expect(packetError({ status: 403, body: {} }, "x")).toBe("Your role does not allow this action.");
    expect(packetBytes(2 * 1024 ** 3)).toBe("2.0 GB");
    expect(packetBytes(-1)).toBe("UNKNOWN");
  });
});

describe("uploadPacketCapture", () => {
  it("sends one multipart file with the CSRF token and reports progress", async () => {
    const original = await vi.importActual<typeof import("../src/auth/adminApi")>("../src/auth/adminApi");
    globalThis.fetch = vi.fn().mockResolvedValue({ json: async () => ({ csrf_token: "csrf-synthetic" }) }) as unknown as typeof fetch;
    const headers: Record<string, string> = {};
    let sent: FormData | null = null;
    const fake = {
      upload: {} as { onprogress?: (event: { lengthComputable: boolean; loaded: number; total: number }) => void },
      open: vi.fn(), setRequestHeader: (name: string, value: string) => { headers[name] = value; },
      withCredentials: false, status: 0, responseText: "",
      onload: null as null | (() => void), onerror: null as null | (() => void),
      send(body: FormData) { sent = body; this.upload.onprogress?.({ lengthComputable: true, loaded: 5, total: 10 });
        this.status = 201; this.responseText = '{"capture_id":"c-1","size_bytes":10,"format":"pcap"}'; this.onload?.(); },
    };
    const progress: number[] = [];
    const result = await original.uploadPacketCapture(CASE, new File([new Uint8Array(10)], "a.pcap"),
      (loaded) => progress.push(loaded), () => fake as unknown as XMLHttpRequest);
    expect(result.capture_id).toBe("c-1");
    expect(fake.open).toHaveBeenCalledWith("POST", `/api/v2/packet-analysis/cases/${CASE}/captures`);
    expect(headers["X-CSRF-Token"]).toBe("csrf-synthetic");
    expect(fake.withCredentials).toBe(true);
    expect(progress).toEqual([5]);
    expect((sent as unknown as FormData).get("file")).toBeInstanceOf(File);
  });
});
