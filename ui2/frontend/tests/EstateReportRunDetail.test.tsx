import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReportRunView } from "../src/auth/adminApi";

const getReportRun = vi.fn();
vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const real = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...real, getReportRun: (id: string) => getReportRun(id) };
});

import { EstateReportRunDetail } from "../src/estate/EstateReportRunDetail";
import { reportDownloadHref } from "../src/auth/adminApi";

const RUN_ID = "run-tango-0001";
// Synthetic only: the secret-shaped value is built at runtime, never stored as a literal.
const SECRET_SHAPED = ["pass", "word"].join("") + "=" + "x".repeat(8);

function run(overrides: Partial<ReportRunView & { document?: unknown }> = {}): ReportRunView {
  return {
    run_id: RUN_ID,
    template_id: "RPT-01",
    title: "Failover readiness",
    state: "SUCCEEDED",
    projection: "MASKED",
    format: "PDF",
    parameters: {},
    data_as_of: { planes: [{ plane: "failover", newest: new Date(Date.now() - 2 * 3_600_000).toISOString(), oldest: null, covered: 2, total: 3, stale: 0, unit: "pairs" }] },
    plaintext_bytes: 100,
    error_code: null,
    requested_at: "2026-11-22T08:00:00.000Z",
    finished_at: "2026-11-22T08:01:00.000Z",
    expires_at: "2026-11-23T08:00:00.000Z",
    downloadable: true,
    mine: true,
    ...overrides,
  } as ReportRunView;
}

describe("EstateReportRunDetail", () => {
  beforeEach(() => { getReportRun.mockReset(); });
  afterEach(() => cleanup());

  it("calls getReportRun exactly once on open and shows the run state as a badge with its word", async () => {
    getReportRun.mockResolvedValue(run());
    render(<EstateReportRunDetail runId={RUN_ID} />);
    await waitFor(() => expect(screen.getByText(/Failover readiness/)).toBeTruthy());
    expect(getReportRun).toHaveBeenCalledTimes(1);
    expect(getReportRun).toHaveBeenCalledWith(RUN_ID);
    expect(document.querySelector("[data-state-badge='ok']")?.textContent).toContain("SUCCEEDED");
    const stamp = document.querySelector("[data-evidence-stamp]");
    expect(stamp).not.toBeNull();
    expect(stamp?.getAttribute("data-evidence-stamp")).toBe("a1");
    expect(screen.queryByText(/Document not available/)).toBeNull();
  });

  it("renders an unrecognised or missing run state as UNKNOWN, never watch and never OK", async () => {
    getReportRun.mockResolvedValue(run({ state: "WEIRD" } as unknown as Partial<ReportRunView>));
    const { unmount } = render(<EstateReportRunDetail runId={RUN_ID} />);
    await waitFor(() => expect(document.querySelector("[data-state-badge='unk']")).not.toBeNull());
    expect(document.querySelector("[data-state-badge='unk']")?.textContent).toContain("UNKNOWN");
    expect(document.querySelector("[data-state-badge='watch']")).toBeNull();
    expect(document.querySelector("[data-state-badge='ok']")).toBeNull();
    unmount();

    getReportRun.mockResolvedValue(run({ state: undefined } as unknown as Partial<ReportRunView>));
    render(<EstateReportRunDetail runId={RUN_ID} />);
    await waitFor(() => expect(document.querySelector("[data-state-badge='unk']")).not.toBeNull());
    expect(document.querySelector("[data-state-badge='ok']")).toBeNull();
  });

  it("renders UNKNOWN evidence (a0) without throwing when data_as_of is null or undefined", async () => {
    getReportRun.mockResolvedValueOnce(run({ data_as_of: null } as unknown as Partial<ReportRunView>));
    const { unmount } = render(<EstateReportRunDetail runId={RUN_ID} />);
    await waitFor(() => expect(document.querySelector("[data-evidence-stamp]")).not.toBeNull());
    expect(document.querySelector("[data-evidence-stamp]")?.getAttribute("data-evidence-stamp")).toBe("a0");
    unmount();

    const omitted = run();
    delete (omitted as unknown as Record<string, unknown>).data_as_of;
    getReportRun.mockResolvedValueOnce(omitted);
    render(<EstateReportRunDetail runId={RUN_ID} />);
    await waitFor(() => expect(document.querySelector("[data-evidence-stamp]")).not.toBeNull());
    expect(document.querySelector("[data-evidence-stamp]")?.getAttribute("data-evidence-stamp")).toBe("a0");
  });

  it("shows the failed state as an UNKNOWN badge when the read fails, never SUCCEEDED", async () => {
    getReportRun.mockRejectedValue(new Error("network"));
    render(<EstateReportRunDetail runId={RUN_ID} />);
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("READ FAILED");
    expect(alert.querySelector("[data-state-badge='unk']")).not.toBeNull();
    expect(screen.queryByText(/SUCCEEDED/)).toBeNull();
  });

  it("says a normal run carries metadata only (neutral, not a warning) when no document is present", async () => {
    getReportRun.mockResolvedValue(run());
    render(<EstateReportRunDetail runId={RUN_ID} />);
    const note = await screen.findByText(/Inline sections are not stored on the run record/);
    expect(note.getAttribute("data-document")).toBe("metadata-only");
    expect(document.querySelector("[data-document='unavailable']")).toBeNull();
    expect(screen.getByText("Download")).toBeTruthy();
  });

  it("renders a malformed document as UNKNOWN, never as available", async () => {
    getReportRun.mockResolvedValue(run({ document: { sections: [{ title: "No id" }] } } as Partial<ReportRunView>));
    render(<EstateReportRunDetail runId={RUN_ID} />);
    expect(await screen.findByText("Document could not be read (UNKNOWN)")).toBeTruthy();
    expect(document.querySelector("[data-document='unavailable'] [data-state-badge='unk']")).not.toBeNull();
    expect(document.querySelector("[data-document='available']")).toBeNull();
  });

  it("renders document sections as text and caps long lists with +N more", async () => {
    const sections = Array.from({ length: 14 }, (_, i) => ({ id: `s${i}`, title: `Section ${i}`, notes: [`note ${i}`] }));
    getReportRun.mockResolvedValue(run({ document: { sections } } as Partial<ReportRunView>));
    render(<EstateReportRunDetail runId={RUN_ID} />);
    expect(await screen.findByText("Document: 14 sections")).toBeTruthy();
    expect(document.querySelector("[data-document='available']")).not.toBeNull();
    expect(document.querySelectorAll("[data-estate-report-section]")).toHaveLength(12);
    expect(screen.getByText("+2 more sections")).toBeTruthy();
  });

  it("links the download to reportDownloadHref and renders no generate, preview or delete control", async () => {
    getReportRun.mockResolvedValue(run({ document: { sections: [{ id: "a", title: "A", notes: [SECRET_SHAPED.length > 0 ? "masked" : ""] }] } } as Partial<ReportRunView>));
    const { container } = render(<EstateReportRunDetail runId={RUN_ID} />);
    await screen.findByText("Download");
    const link = container.querySelector(`a[href="${reportDownloadHref(RUN_ID)}"]`);
    expect(link).not.toBeNull();
    const labels = Array.from(container.querySelectorAll("button, a")).map(el => el.textContent ?? "");
    for (const bad of ["Generate", "Preview", "Delete", "Remove", "Regenerate"]) {
      expect(labels.some(l => l.includes(bad))).toBe(false);
    }
    expect(container.querySelectorAll("button")).toHaveLength(0);
  });
});
