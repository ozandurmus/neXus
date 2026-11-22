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

  it("calls getReportRun exactly once on open and shows the run state", async () => {
    getReportRun.mockResolvedValue(run());
    render(<EstateReportRunDetail runId={RUN_ID} />);
    await waitFor(() => expect(screen.getByText(/Failover readiness/)).toBeTruthy());
    expect(getReportRun).toHaveBeenCalledTimes(1);
    expect(getReportRun).toHaveBeenCalledWith(RUN_ID);
    expect(screen.getByText("SUCCEEDED")).toBeTruthy();
    expect(screen.getByText(/Evidence age: 2 h/)).toBeTruthy();
  });

  it("shows the failed state when the read fails", async () => {
    getReportRun.mockRejectedValue(new Error("network"));
    render(<EstateReportRunDetail runId={RUN_ID} />);
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("READ FAILED");
    expect(screen.queryByText("SUCCEEDED")).toBeNull();
  });

  it("says Document not available when the document is missing or malformed, never OK", async () => {
    getReportRun.mockResolvedValue(run());
    const { unmount } = render(<EstateReportRunDetail runId={RUN_ID} />);
    expect(await screen.findByText("Document not available")).toBeTruthy();
    unmount();

    getReportRun.mockResolvedValue(run({ document: { sections: [{ title: "No id" }] } } as Partial<ReportRunView>));
    render(<EstateReportRunDetail runId={RUN_ID} />);
    await waitFor(() => expect(screen.getAllByText("Document not available").length).toBeGreaterThan(0));
    expect(document.querySelector("[data-document='available']")).toBeNull();
  });

  it("renders document sections as text and caps long lists with +N more", async () => {
    const sections = Array.from({ length: 14 }, (_, i) => ({ id: `s${i}`, title: `Section ${i}`, notes: [`note ${i}`] }));
    getReportRun.mockResolvedValue(run({ document: { sections } } as Partial<ReportRunView>));
    render(<EstateReportRunDetail runId={RUN_ID} />);
    expect(await screen.findByText("Document: 14 sections")).toBeTruthy();
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
