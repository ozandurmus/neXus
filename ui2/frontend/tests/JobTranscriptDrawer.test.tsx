import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, afterEach } from "vitest";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { canReadJobTranscript, JobTranscriptDrawer, transcriptAsText } from "../src/screens/JobTranscriptDrawer";
import type { JobTranscriptEntry } from "../src/auth/adminApi";

const entries: JobTranscriptEntry[] = [
  { seq: 1, at: "2026-09-27T10:00:00Z", elapsedMs: 4, channel: "ssh", kind: "command", text: "show synthetic" },
  { seq: 2, at: "2026-09-27T10:00:01Z", elapsedMs: 7, channel: "https", kind: "response", text: "synthetic answer" },
];

function session(roleTokens: string[]): SessionInfo {
  return { displayName: "Synthetic", roleTokens, permissions: [], onSignOut: () => undefined };
}

afterEach(() => vi.unstubAllGlobals());

describe("backup job transcript access", () => {
  it.each([["role:security_admin"], ["role:backup_admin"]])("shows the action for %s", (role) => {
    render(<SessionContext.Provider value={session([role])}><JobTranscriptDrawer jobId="job-synthetic" hasTranscript /></SessionContext.Provider>);
    expect(screen.getByRole("button", { name: "Transcript" })).toBeInTheDocument();
  });

  it.each([["role:viewer"], ["role:operator"], ["role:replay_viewer"]])("hides the action for %s", (role) => {
    render(<SessionContext.Provider value={session([role])}><JobTranscriptDrawer jobId="job-synthetic" hasTranscript /></SessionContext.Provider>);
    expect(screen.queryByRole("button", { name: "Transcript" })).toBeNull();
  });

  it("renders both channels and filters entries by search", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(entries))));
    render(<SessionContext.Provider value={session(["role:backup_admin"])}><JobTranscriptDrawer jobId="job-synthetic" hasTranscript /></SessionContext.Provider>);
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
    expect(canReadJobTranscript(["role:viewer", "role:backup_admin"])).toBe(true);
    expect(canReadJobTranscript(["role:replay_viewer", "role:backup_admin"])).toBe(false);
  });
});
