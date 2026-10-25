import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi, afterEach } from "vitest";
import { JobTranscriptDrawer, transcriptAsText } from "../src/screens/JobTranscriptDrawer";
import { istanbulStamp, transcriptFileName } from "../src/screens/transcriptDownload";
import type { JobTranscriptEntry } from "../src/auth/adminApi";

const entries: JobTranscriptEntry[] = [
  { seq: 1, at: "2026-09-27T10:00:00Z", elapsedMs: 4, channel: "ssh", kind: "command", text: "show synthetic" },
  { seq: 2, at: "2026-09-27T10:00:01Z", elapsedMs: 7, channel: "https", kind: "response", text: "synthetic answer" },
];

/** Captures every file the page would save: the blob handed to the browser and the name the link carried. */
function captureDownloads() {
  const blobs: Blob[] = [];
  const names: string[] = [];
  Object.defineProperty(URL, "createObjectURL", { configurable: true, value: vi.fn((blob: Blob) => { blobs.push(blob); return `blob:${blobs.length}`; }) });
  Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) { names.push(this.download); });
  const text = (index: number) => new Promise<string>((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.readAsText(blobs[index]);
  });
  return { blobs, names, text };
}

/** The open right-hand panel; the row's own buttons stay in the document beside it. */
const panel = () => within(document.querySelector(".MuiDrawer-paper") as HTMLElement);

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe("backup job transcript access", () => {
  it("shows the Transcript and Download actions when the server grants transcript access", () => {
    render(<JobTranscriptDrawer jobId="job-synthetic" hasTranscript />);
    expect(screen.getByRole("button", { name: "Transcript" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Download" })).toBeInTheDocument();
  });

  it("hides both actions when the server does not grant transcript access", () => {
    render(<JobTranscriptDrawer jobId="job-synthetic" hasTranscript={false} />);
    expect(screen.queryByRole("button", { name: "Transcript" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Download" })).toBeNull();
  });

  it("renders both channels, downloads from the panel header and filters entries by search", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(entries))));
    render(<JobTranscriptDrawer jobId="job-synthetic" jobType="backup" submittedAt="2026-09-27T10:00:00Z" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByText("show synthetic")).toBeInTheDocument();
    expect(screen.getByText("synthetic answer")).toBeInTheDocument();
    expect(screen.getByText("SSH")).toBeInTheDocument();
    expect(screen.getByText("HTTPS")).toBeInTheDocument();
    const saved = captureDownloads();
    fireEvent.click(panel().getByRole("button", { name: "Download" }));
    expect(saved.blobs[0].type).toBe("text/plain;charset=utf-8");
    expect(saved.names).toEqual(["backup-20260927-1300-job-synt.txt"]);
    expect(await saved.text(0)).toBe("2026-09-27T10:00:00Z\tssh\tcommand\tshow synthetic\n2026-09-27T10:00:01Z\thttps\tresponse\tsynthetic answer\n");
    fireEvent.change(screen.getByLabelText("Search transcript"), { target: { value: "answer" } });
    await waitFor(() => expect(screen.queryByText("show synthetic")).toBeNull());
    expect(screen.getByText("synthetic answer")).toBeInTheDocument();
  });

  it("downloads from the row without opening the panel, through the same transcript read", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(entries)));
    vi.stubGlobal("fetch", fetch);
    const saved = captureDownloads();
    render(<JobTranscriptDrawer jobId="job-synthetic" jobType="cp_gateway_backup" submittedAt="2026-09-27T10:00:00Z" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Download" }));
    await waitFor(() => expect(saved.names).toEqual(["cp-gateway-backup-20260927-1300-job-synt.txt"]));
    expect(fetch).toHaveBeenCalledWith("/jobs/job-synthetic/transcript", expect.anything());
    expect(await saved.text(0)).toContain("show synthetic");
    expect(screen.queryByLabelText("Search transcript")).toBeNull();
  });

  it("downloads the transcript of the row it belongs to, even after another row's panel was opened and closed", async () => {
    const fetch = vi.fn((input: RequestInfo | URL) => {
      const which = String(input).includes("job-a-") ? "a" : "b";
      return Promise.resolve(new Response(JSON.stringify([{ seq: 1, at: `2026-09-27T10:00:00Z`, elapsedMs: 1, channel: "ssh", kind: "response", text: `output of ${which}` }])));
    });
    vi.stubGlobal("fetch", fetch);
    const saved = captureDownloads();
    render(<>
      <div data-testid="row-a"><JobTranscriptDrawer jobId="job-a-1111" jobType="backup" submittedAt="2026-09-27T10:00:00Z" hasTranscript /></div>
      <div data-testid="row-b"><JobTranscriptDrawer jobId="job-b-2222" jobType="cp_gateway_backup" submittedAt="2026-09-28T21:30:00Z" hasTranscript /></div>
    </>);
    const rowA = within(screen.getByTestId("row-a"));
    const rowB = within(screen.getByTestId("row-b"));
    fireEvent.click(rowA.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByText("output of a")).toBeInTheDocument();
    fireEvent.click(panel().getByRole("button", { name: "Close" }));
    fireEvent.click(rowB.getByRole("button", { name: "Download" }));
    // Turkey time crosses midnight here: 21:30Z on the 28th is 00:30 on the 29th.
    await waitFor(() => expect(saved.names).toEqual(["cp-gateway-backup-20260929-0030-job-b-22.txt"]));
    expect(fetch).toHaveBeenLastCalledWith("/jobs/job-b-2222/transcript", expect.anything());
    const fileB = await saved.text(0);
    expect(fileB).toContain("output of b");
    expect(fileB).not.toContain("output of a");
    // The panel of row A still saves A, after B was downloaded.
    fireEvent.click(rowA.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByText("output of a")).toBeInTheDocument();
    fireEvent.click(panel().getByRole("button", { name: "Download" }));
    expect(saved.names[1]).toBe("backup-20260927-1300-job-a-11.txt");
    const fileA = await saved.text(1);
    expect(fileA).toContain("output of a");
    expect(fileA).not.toContain("output of b");
  });

  it("names the file from the first entry and a plain job prefix when the row knows neither type nor instant", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(entries))));
    const saved = captureDownloads();
    render(<JobTranscriptDrawer jobId="job-synthetic" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Download" }));
    await waitFor(() => expect(saved.names).toEqual(["job-20260927-1300-job-synt.txt"]));
  });

  it("reports a refused row download with the stable code and saves nothing", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 403 })));
    const saved = captureDownloads();
    render(<JobTranscriptDrawer jobId="job-forbidden" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Download" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Transcript could not be downloaded (TRANSCRIPT_FORBIDDEN).");
    expect(saved.names).toEqual([]);
    expect(screen.queryByLabelText("Search transcript")).toBeNull();
  });

  it("shows an explicit bug message for an empty transcript", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]")));
    render(<JobTranscriptDrawer jobId="job-empty" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByText("No steps were recorded (bug)")).toBeInTheDocument();
  });

  it("renders transfer, decision, and verdict steps", async () => {
    const steps = ["transfer", "note", "verdict"].map((kind, i) => ({
      seq: i + 1, at: "2026-10-02T10:00:00Z", elapsedMs: i, channel: "backup" as const, kind, text: `synthetic ${kind}`,
    }));
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(steps))));
    render(<JobTranscriptDrawer jobId="job-steps" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    for (const step of steps) expect(await screen.findByText(step.text)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search transcript"), { target: { value: "no-match" } });
    expect(await screen.findByText("No matching steps.")).toBeInTheDocument();
    expect(screen.queryByText("No steps were recorded (bug)")).toBeNull();
  });

  it.each(["TRANSCRIPT_DECRYPT_FAILED", "TRANSCRIPT_PARSE_FAILED", "TRANSCRIPT_MISSING"])("shows the stable server error %s", async (code) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: code }), { status: code === "TRANSCRIPT_MISSING" ? 409 : 500 })));
    render(<JobTranscriptDrawer jobId="job-error" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(`Transcript could not be loaded (${code}).`);
    expect(screen.queryByText("No steps were recorded (bug)")).toBeNull();
    expect(panel().getByRole("button", { name: "Download" })).toBeDisabled();
  });

  it.each(["[", JSON.stringify({}), JSON.stringify([{}])])("rejects malformed successful content: %s", async (body) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(body)));
    render(<JobTranscriptDrawer jobId="job-broken" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("TRANSCRIPT_PARSE_FAILED");
    expect(panel().getByRole("button", { name: "Download" })).toBeDisabled();
  });

  it("keeps forbidden content distinct from missing steps", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 403 })));
    render(<JobTranscriptDrawer jobId="job-forbidden" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("TRANSCRIPT_FORBIDDEN");
  });

  it("does not display arbitrary exception details", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: "synthetic private detail" }), { status: 500 })));
    render(<JobTranscriptDrawer jobId="job-private-error" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("TRANSCRIPT_LOAD_FAILED");
    expect(screen.queryByText(/synthetic private detail/)).toBeNull();
  });
});

describe("transcript text file", () => {
  it("writes one line per entry in order: ISO instant, channel with session id when present, kind, text", () => {
    const text = transcriptAsText([{ ...entries[0], sessionId: "s-1" }, entries[1]]);
    expect(text).toBe("2026-09-27T10:00:00Z\tssh/s-1\tcommand\tshow synthetic\n2026-09-27T10:00:01Z\thttps\tresponse\tsynthetic answer\n");
    expect(transcriptAsText([])).toBe("");
  });

  it("keeps multi-line device output readable, its continuation lines marked by a leading tab", () => {
    const text = transcriptAsText([{ ...entries[1], text: "line one\r\nline two\nline three" }]);
    expect(text).toBe("2026-09-27T10:00:01Z\thttps\tresponse\tline one\n\tline two\n\tline three\n");
  });

  it("names the file <job-type>-<YYYYMMDD-HHmm>-<short job id>.txt in Turkey time", () => {
    expect(istanbulStamp("2026-09-27T10:00:00Z")).toBe("20260927-1300");
    expect(istanbulStamp("2026-12-31T22:15:00Z")).toBe("20270101-0115");
    expect(istanbulStamp("not a time")).toBe("unknown-time");
    expect(transcriptFileName({ jobId: "3f9c1a2b-0000-4000-8000-000000000000", jobType: "cp_gateway_backup", at: "2026-10-25T06:12:00Z" }, [])).toBe("cp-gateway-backup-20261025-0912-3f9c1a2b.txt");
    expect(transcriptFileName({ jobId: "job-synthetic" }, entries)).toBe("job-20260927-1300-job-synt.txt");
    expect(transcriptFileName({ jobId: "job-x", jobType: "Policy Collection!" }, [])).toMatch(/^policy-collection-\d{8}-\d{4}-job-x\.txt$/);
  });
});
