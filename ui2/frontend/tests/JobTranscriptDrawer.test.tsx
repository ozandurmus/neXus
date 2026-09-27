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

  it("builds a downloadable text representation", () => {
    expect(transcriptAsText(entries)).toContain("2026-09-27T10:00:00Z (+4 ms) [SSH] command\nshow synthetic");
  });
});
