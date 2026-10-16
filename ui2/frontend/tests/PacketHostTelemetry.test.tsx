import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PacketAnalysisScreen } from "../src/screens/PacketAnalysisScreen";
import { PacketHostTelemetry, packetAlignmentText } from "../src/screens/packetanalysis/PacketHostTelemetry";
import * as api from "../src/auth/adminApi";
import type { PacketCaseDetail, PacketCaseList, PacketTelemetry } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async importOriginal => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  listPacketCases: vi.fn(), getPacketCase: vi.fn(), uploadPacketTelemetry: vi.fn(), alignPacketTelemetry: vi.fn(),
  deletePacketTelemetry: vi.fn(), getPacketCaseState: vi.fn(),
}));

const CASE = "11111111-2222-4333-8444-555555555555";
const T1 = "99999999-8888-4777-8666-555555555555";
const T2 = "99999999-8888-4777-8666-666666666666";
const operator = { run: true, raw_view: false, masked: false };
const aiview = { run: false, raw_view: false, masked: true };

const vmstat: PacketTelemetry = {
  telemetry_id: T1, display_name: "srv-vmstat.txt", size_bytes: 2048, detected_format: "vmstat", recognized: true,
  recognition_note: null, time_zone: null, zone_confirmed: false, offset_seconds: 0, anchor_local: null,
  sha256: "e".repeat(64), uploaded_by: "synthetic-operator", uploaded_at: "2026-10-16T08:00:00Z", aligned_at: null,
};
const table: PacketTelemetry = {
  ...vmstat, telemetry_id: T2, display_name: "export.csv", detected_format: "unknown", recognized: false,
  recognition_note: "NO_KNOWN_FORMAT", time_zone: "Europe/Istanbul", zone_confirmed: true, offset_seconds: -3,
  anchor_local: "2026-10-08 13:00:00",
};

function renderPanel(overrides: Partial<React.ComponentProps<typeof PacketHostTelemetry>> = {}) {
  const onChanged = vi.fn();
  const onError = vi.fn();
  const onNotice = vi.fn();
  render(<ThemeProvider theme={m3Theme}><PacketHostTelemetry caseId={CASE} items={[vmstat, table]} permissions={operator}
    maxBytes={200 * 1024 * 1024} onChanged={onChanged} onError={onError} onNotice={onNotice} {...overrides} /></ThemeProvider>);
  return { onChanged, onError, onNotice };
}

beforeEach(() => { vi.clearAllMocks(); });

describe("Host telemetry panel", () => {
  it("lists each file with what it was recognised as and says plainly how its clock will be read", () => {
    renderPanel();
    const list = screen.getByRole("table", { name: "Host telemetry" });
    expect(within(list).getByText("srv-vmstat.txt")).toBeInTheDocument();
    expect(within(list).getByText("vmstat")).toBeInTheDocument();
    expect(within(list).getByText("UTC (assumed, not confirmed)")).toBeInTheDocument();
    expect(within(list).getByText("Not recognized")).toBeInTheDocument();
    expect(within(list).getByText(/No sar, vmstat, pidstat, qdisc\/softnet snapshot or syslog structure/)).toBeInTheDocument();
    expect(within(list).getByText("Europe/Istanbul (confirmed), clock offset -3 s, first line at 2026-10-08 13:00:00")).toBeInTheDocument();
    expect(packetAlignmentText(vmstat)).toBe("UTC (assumed, not confirmed)");
  });

  it("uploads a file, tells whether it was recognised and asks the parent to reload", async () => {
    vi.mocked(api.uploadPacketTelemetry).mockImplementation((_id, _file, onProgress) => { onProgress(5, 10); return Promise.resolve(vmstat); });
    const props = renderPanel();
    const file = new File([new Uint8Array(10)], "srv-vmstat.txt");
    fireEvent.change(screen.getByLabelText("Host telemetry file"), { target: { files: [file] } });
    await waitFor(() => expect(props.onChanged).toHaveBeenCalled());
    expect(api.uploadPacketTelemetry).toHaveBeenCalledWith(CASE, file, expect.any(Function));
    expect(props.onNotice).toHaveBeenLastCalledWith(expect.stringContaining("Confirm its time zone"));
    vi.mocked(api.uploadPacketTelemetry).mockResolvedValue(table);
    fireEvent.change(screen.getByLabelText("Host telemetry file"), { target: { files: [file] } });
    await waitFor(() => expect(props.onNotice).toHaveBeenLastCalledWith(expect.stringContaining("format was not recognized")));
  });

  it("refuses a file over the server's limit before sending a byte and shows the server's refusal otherwise", async () => {
    const props = renderPanel({ maxBytes: 1024 });
    fireEvent.change(screen.getByLabelText("Host telemetry file"), { target: { files: [new File([new Uint8Array(2048)], "big.txt")] } });
    expect(props.onError).toHaveBeenCalledWith("A telemetry file may be at most 1.0 KB.");
    expect(api.uploadPacketTelemetry).not.toHaveBeenCalled();
    vi.mocked(api.uploadPacketTelemetry).mockRejectedValue({ status: 415, body: { error: "NOT_A_TEXT_FILE", reason: "Only text files are accepted as host telemetry (a binary file was sent)" } });
    fireEvent.change(screen.getByLabelText("Host telemetry file"), { target: { files: [new File([new Uint8Array(10)], "x.bin")] } });
    await waitFor(() => expect(props.onError).toHaveBeenLastCalledWith(expect.stringContaining("Only text files are accepted")));
  });

  it("saves the zone, offset and start the user confirmed, and an empty zone goes back to unconfirmed", async () => {
    vi.mocked(api.alignPacketTelemetry).mockResolvedValue({ ...vmstat, time_zone: "Europe/Istanbul", zone_confirmed: true });
    const props = renderPanel();
    fireEvent.click(within(screen.getByRole("table", { name: "Host telemetry" })).getAllByRole("button", { name: "Set clock" })[0]);
    const form = await screen.findByLabelText("Clock alignment form");
    fireEvent.change(within(form).getByLabelText("Time zone"), { target: { value: " Europe/Istanbul " } });
    fireEvent.change(within(form).getByLabelText("Clock offset (s)"), { target: { value: "5" } });
    fireEvent.change(within(form).getByLabelText("First line time"), { target: { value: "2026-10-08 13:00:00" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save alignment" }));
    await waitFor(() => expect(api.alignPacketTelemetry).toHaveBeenCalledWith(T1,
      { time_zone: "Europe/Istanbul", offset_seconds: 5, anchor_local: "2026-10-08 13:00:00" }));
    expect(props.onChanged).toHaveBeenCalled();
    expect(props.onNotice).toHaveBeenLastCalledWith("Clock alignment saved.");

    fireEvent.click(within(screen.getByRole("table", { name: "Host telemetry" })).getAllByRole("button", { name: "Set clock" })[1]);
    const second = await screen.findByLabelText("Clock alignment form");
    expect(within(second).getByLabelText("Time zone")).toHaveValue("Europe/Istanbul");
    fireEvent.change(within(second).getByLabelText("Time zone"), { target: { value: "" } });
    fireEvent.change(within(second).getByLabelText("First line time"), { target: { value: "" } });
    fireEvent.click(within(second).getByRole("button", { name: "Save alignment" }));
    await waitFor(() => expect(api.alignPacketTelemetry).toHaveBeenLastCalledWith(T2,
      { time_zone: null, offset_seconds: -3, anchor_local: null }));
  });

  it("does not send an offset that is not a whole number of seconds within a day", async () => {
    const props = renderPanel();
    fireEvent.click(within(screen.getByRole("table", { name: "Host telemetry" })).getAllByRole("button", { name: "Set clock" })[0]);
    const form = await screen.findByLabelText("Clock alignment form");
    for (const bad of ["soon", "1.5", "90000"]) {
      fireEvent.change(within(form).getByLabelText("Clock offset (s)"), { target: { value: bad } });
      fireEvent.click(within(form).getByRole("button", { name: "Save alignment" }));
    }
    const messages = props.onError.mock.calls.map(call => call[0]).filter(Boolean);
    expect(messages).toEqual(Array(3).fill("The clock offset must be a whole number of seconds within ±86400."));
    expect(api.alignPacketTelemetry).not.toHaveBeenCalled();
  });

  it("deletes a file and shows the server's refusal when it cannot", async () => {
    vi.mocked(api.deletePacketTelemetry).mockResolvedValue({ deleted: true });
    const props = renderPanel();
    fireEvent.click(within(screen.getByRole("table", { name: "Host telemetry" })).getAllByRole("button", { name: "Delete" })[1]);
    await waitFor(() => expect(api.deletePacketTelemetry).toHaveBeenCalledWith(T2));
    expect(props.onChanged).toHaveBeenCalled();
    vi.mocked(api.deletePacketTelemetry).mockRejectedValue({ status: 404, body: { error: "TELEMETRY_NOT_FOUND", reason: "No telemetry file with that id" } });
    fireEvent.click(within(screen.getByRole("table", { name: "Host telemetry" })).getAllByRole("button", { name: "Delete" })[0]);
    await waitFor(() => expect(props.onError).toHaveBeenLastCalledWith("No telemetry file with that id"));
  });

  it("offers no write control to a reader without the run role", () => {
    renderPanel({ permissions: aiview, items: [{ ...vmstat, display_name: "FILE-JULIET-01", sha256: undefined, uploaded_by: undefined }] });
    expect(screen.getByText("FILE-JULIET-01")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add host telemetry" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Set clock" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
    expect(screen.queryByLabelText("Host telemetry file")).toBeNull();
  });
});

describe("Host telemetry in the case detail", () => {
  const listing = (permissions = operator): PacketCaseList => ({
    cases: [{ case_id: CASE, title: "Synthetic loss case", created_at: "2026-10-15T08:00:00Z", expires_at: "2026-10-22T08:00:00Z",
      state: "ANALYZED", total_bytes: 3 * 1024 * 1024, capture_count: 1, payload_zeroing: false, latest_job_id: null }],
    permissions, limits: { max_file_bytes: 2 ** 31, max_store_bytes: 100 * 2 ** 30, used_bytes: 5 * 2 ** 20, retention_days: 7, max_telemetry_bytes: 200 * 2 ** 20 },
  });
  const detail = (telemetry: PacketTelemetry[]): PacketCaseDetail => ({ ...listing().cases[0], permissions: operator, captures: [], runs: [], telemetry });

  it("shows the panel inside the case and reloads the case after a change", async () => {
    vi.mocked(api.listPacketCases).mockResolvedValue(listing());
    vi.mocked(api.getPacketCase).mockResolvedValue(detail([vmstat]));
    vi.mocked(api.getPacketCaseState).mockResolvedValue({} as never);
    vi.mocked(api.deletePacketTelemetry).mockResolvedValue({ deleted: true });
    render(<ThemeProvider theme={m3Theme}><PacketAnalysisScreen /></ThemeProvider>);
    fireEvent.click(await screen.findByRole("button", { name: "Synthetic loss case" }));
    const card = await screen.findByLabelText("Case detail");
    expect(within(card).getByRole("table", { name: "Host telemetry" })).toBeInTheDocument();
    expect(within(card).getByText("srv-vmstat.txt")).toBeInTheDocument();
    vi.mocked(api.getPacketCase).mockResolvedValue(detail([]));
    fireEvent.click(within(card).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("No host telemetry attached")).toBeInTheDocument();
    expect(await screen.findByText("Telemetry file deleted.")).toBeInTheDocument();
  });
});

describe("telemetry API calls", () => {
  it("upload sends one multipart file to the telemetry route with the CSRF token; alignment is a PUT and delete a DELETE", async () => {
    const original = await vi.importActual<typeof import("../src/auth/adminApi")>("../src/auth/adminApi");
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (String(url) === "/session/status") return { json: async () => ({ csrf_token: "csrf-synthetic" }) };
      return { ok: true, status: 200, headers: new Headers(), json: async () => (init?.method === "DELETE" ? { deleted: true } : { telemetry_id: T1 }) };
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    const opened: string[][] = [];
    const fake = {
      upload: {} as { onprogress?: (event: { lengthComputable: boolean; loaded: number; total: number }) => void },
      open: (method: string, url: string) => { opened.push([method, url]); }, setRequestHeader: vi.fn(),
      withCredentials: false, status: 0, responseText: "", onload: null as null | (() => void), onerror: null as null | (() => void),
      send() { this.status = 201; this.responseText = `{"telemetry_id":"${T1}"}`; this.onload?.(); },
    };
    const stored = await original.uploadPacketTelemetry(CASE, new File([new Uint8Array(3)], "a.txt"), () => undefined,
      () => fake as unknown as XMLHttpRequest);
    expect(stored.telemetry_id).toBe(T1);
    expect(opened).toEqual([["POST", `/api/v2/packet-analysis/cases/${CASE}/telemetry`]]);
    expect(fake.setRequestHeader).toHaveBeenCalledWith("X-CSRF-Token", "csrf-synthetic");
    await original.alignPacketTelemetry(T1, { time_zone: "UTC", offset_seconds: 0, anchor_local: null });
    const put = fetchMock.mock.calls.find(call => (call[1] as RequestInit | undefined)?.method === "PUT");
    expect(String(put?.[0])).toBe(`/api/v2/packet-analysis/telemetry/${T1}`);
    expect(JSON.parse(String((put?.[1] as RequestInit).body))).toEqual({ time_zone: "UTC", offset_seconds: 0, anchor_local: null });
    await original.deletePacketTelemetry(T1);
    const del = fetchMock.mock.calls.find(call => (call[1] as RequestInit | undefined)?.method === "DELETE");
    expect(String(del?.[0])).toBe(`/api/v2/packet-analysis/telemetry/${T1}`);
    expect((del?.[1] as RequestInit).headers).toEqual({ "X-CSRF-Token": "csrf-synthetic" });
  });
});
