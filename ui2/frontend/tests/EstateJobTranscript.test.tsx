import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { JobTranscriptEntry } from "../src/auth/adminApi";

const getJobTranscript = vi.fn();
vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, getJobTranscript: (...args: unknown[]) => getJobTranscript(...args) };
});

import { EstateJobTranscriptDrawer } from "../src/estate/EstateJobTranscript";

const JOB_ID = "FW-TANGO-04-job-synthetic-01";
const entries: JobTranscriptEntry[] = [
  { seq: 2, at: "2026-11-22T10:00:02Z", elapsedMs: 7, channel: "https", kind: "response", text: "synthetic answer" },
  { seq: 1, at: "2026-11-22T10:00:01Z", elapsedMs: 4, channel: "ssh", kind: "command", text: "synthetic command" },
];

beforeEach(() => { getJobTranscript.mockReset(); });
afterEach(() => { vi.restoreAllMocks(); });

describe("estate job transcript drawer", () => {
  it("reads the transcript once on open and shows the lines in order", async () => {
    getJobTranscript.mockResolvedValue(entries);
    render(<EstateJobTranscriptDrawer jobId={JOB_ID} onClose={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText("Transcript lines")).toBeInTheDocument());
    expect(getJobTranscript).toHaveBeenCalledTimes(1);
    expect(getJobTranscript).toHaveBeenCalledWith(JOB_ID);
    const items = within(screen.getByLabelText("Transcript lines")).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("synthetic command");
    expect(items[1]).toHaveTextContent("synthetic answer");
    expect(screen.getByText("Showing 2 lines of 2 lines as read")).toBeInTheDocument();
  });

  it("is read-only: no cancel or reconcile control", async () => {
    getJobTranscript.mockResolvedValue(entries);
    render(<EstateJobTranscriptDrawer jobId={JOB_ID} onClose={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText("Transcript lines")).toBeInTheDocument());
    const dialog = screen.getByRole("dialog", { name: "Job transcript" });
    expect(within(dialog).queryByRole("button", { name: /cancel|reconcile|retry|run/i })).toBeNull();
    expect(within(dialog).getAllByRole("button").map((b) => b.textContent)).toEqual(["Close"]);
  });

  it("links to the standard view with standard=1", async () => {
    getJobTranscript.mockResolvedValue(entries);
    render(<EstateJobTranscriptDrawer jobId={JOB_ID} onClose={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText("Transcript lines")).toBeInTheDocument());
    const link = screen.getByRole("link", { name: /Open in standard view/ });
    expect(new URL(link.getAttribute("href") ?? "", "https://example.invalid/").searchParams.get("standard")).toBe("1");
  });

  it("caps a long transcript with a '+N more lines not shown' footer", async () => {
    const many: JobTranscriptEntry[] = Array.from({ length: 103 }, (_, i) => ({
      seq: i + 1, at: "2026-11-22T10:00:00Z", elapsedMs: 1, channel: "ssh", kind: "command", text: `synthetic line ${i + 1}`,
    }));
    getJobTranscript.mockResolvedValue(many);
    render(<EstateJobTranscriptDrawer jobId={JOB_ID} onClose={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText("Transcript lines")).toBeInTheDocument());
    expect(within(screen.getByLabelText("Transcript lines")).getAllByRole("listitem")).toHaveLength(100);
    expect(screen.getByText("+3 more lines not shown")).toBeInTheDocument();
  });

  it("marks a failed read UNKNOWN: badge and a0 stamp, never a1", async () => {
    getJobTranscript.mockRejectedValueOnce({ status: 500, body: {} });
    render(<EstateJobTranscriptDrawer jobId={JOB_ID} onClose={() => {}} />);
    await screen.findByRole("alert");
    // The drawer is a MUI portal: the marks sit in document.body, not in the render container.
    expect(document.body.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    const stamps = Array.from(document.body.querySelectorAll("[data-evidence-stamp]")).map((el) => el.getAttribute("data-evidence-stamp"));
    expect(stamps).toContain("a0");
    expect(stamps).not.toContain("a1");
  });

  it("says no transcript lines for an empty transcript, and UNKNOWN for a failed read", async () => {
    getJobTranscript.mockResolvedValueOnce([]);
    const { unmount } = render(<EstateJobTranscriptDrawer jobId={JOB_ID} onClose={() => {}} />);
    expect(await screen.findByText("No transcript lines")).toBeInTheDocument();
    unmount();

    getJobTranscript.mockRejectedValueOnce({ status: 500, body: {} });
    render(<EstateJobTranscriptDrawer jobId={JOB_ID} onClose={() => {}} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("UNKNOWN");
    expect(screen.queryByText("No transcript lines")).toBeNull();
  });
});
