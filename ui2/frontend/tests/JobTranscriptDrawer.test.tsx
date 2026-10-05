import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, afterEach } from "vitest";
import { JobTranscriptDrawer, transcriptAsText } from "../src/screens/JobTranscriptDrawer";
import type { JobTranscriptEntry } from "../src/auth/adminApi";

const entries: JobTranscriptEntry[] = [
  { seq: 1, at: "2026-09-27T10:00:00Z", elapsedMs: 4, channel: "ssh", kind: "command", text: "show synthetic" },
  { seq: 2, at: "2026-09-27T10:00:01Z", elapsedMs: 7, channel: "https", kind: "response", text: "synthetic answer" },
];

afterEach(() => vi.unstubAllGlobals());

describe("backup job transcript access", () => {
  it("shows the action when the server grants transcript access", () => {
    render(<JobTranscriptDrawer jobId="job-synthetic" hasTranscript />);
    expect(screen.getByRole("button", { name: "Transcript" })).toBeInTheDocument();
  });

  it("hides the action when the server does not grant transcript access", () => {
    render(<JobTranscriptDrawer jobId="job-synthetic" hasTranscript={false} />);
    expect(screen.queryByRole("button", { name: "Transcript" })).toBeNull();
  });

  it("renders both channels and filters entries by search", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(entries))));
    render(<JobTranscriptDrawer jobId="job-synthetic" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByText("show synthetic")).toBeInTheDocument();
    expect(screen.getByText("synthetic answer")).toBeInTheDocument();
    expect(screen.getByText("SSH")).toBeInTheDocument();
    expect(screen.getByText("HTTPS")).toBeInTheDocument();
    const createUrl = vi.fn().mockReturnValue("blob:transcript");
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createUrl });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: vi.fn() });
    const clickLink = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    fireEvent.click(screen.getByRole("button", { name: "Download (.txt)" }));
    expect((createUrl.mock.calls[0][0] as Blob).type).toBe("text/plain;charset=utf-8");
    expect(clickLink).toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("Search transcript"), { target: { value: "answer" } });
    await waitFor(() => expect(screen.queryByText("show synthetic")).toBeNull());
    expect(screen.getByText("synthetic answer")).toBeInTheDocument();
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
    expect(screen.getByRole("button", { name: "Download (.txt)" })).toBeDisabled();
  });

  it.each(["[", JSON.stringify({}), JSON.stringify([{}])])("rejects malformed successful content: %s", async (body) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(body)));
    render(<JobTranscriptDrawer jobId="job-broken" hasTranscript />);
    fireEvent.click(screen.getByRole("button", { name: "Transcript" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("TRANSCRIPT_PARSE_FAILED");
    expect(screen.getByRole("button", { name: "Download (.txt)" })).toBeDisabled();
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

  it("builds a downloadable text representation", () => {
    expect(transcriptAsText(entries)).toContain("2026-09-27T10:00:00Z (+4 ms) [SSH] command\nshow synthetic");
  });
});
