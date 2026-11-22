import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  generateReport,
  getReportCatalogue,
  listReportRuns,
  previewReport,
  type ReportCatalogue,
  type ReportDocumentView,
} from "../src/auth/adminApi";
import { EstateReportsScreen } from "../src/estate/EstateReportsScreen";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getReportCatalogue: vi.fn(),
    listReportRuns: vi.fn(),
    previewReport: vi.fn(),
    generateReport: vi.fn(),
  };
});

const CATALOGUE: ReportCatalogue = {
  templates: [
    {
      template_id: "TPL-SYNTH-01",
      title: "Synthetic estate summary",
      audience: "Operations",
      question: "Is the synthetic estate healthy?",
      formats: ["PDF", "CSV"],
      framework_parameter: true,
      missing_reads: [],
    },
    {
      template_id: "TPL-SYNTH-02",
      title: "Synthetic refused pack",
      audience: "Audit",
      question: "Who may read this?",
      formats: ["PDF"],
      framework_parameter: false,
      missing_reads: ["synthetic_read"],
    },
  ],
  frameworks: ["ALL"],
  permissions: { raw_view: false } as ReportCatalogue["permissions"],
  retention_days: 7,
};

const DOC: ReportDocumentView = {
  template_id: "TPL-SYNTH-01",
  title: "Synthetic estate summary",
  audience: "Operations",
  question: "Is the synthetic estate healthy?",
  generated_at: new Date().toISOString(),
  projection: "MASKED",
  parameters: {},
  data_as_of: [
    {
      plane: "SYNTH_PLANE",
      newest: new Date(Date.now() - 5 * 60_000).toISOString(),
      oldest: new Date(Date.now() - 3 * 86_400_000).toISOString(),
      covered: 2,
      total: 2,
      stale: 0,
      unit: "devices",
    },
  ],
  kpis: [],
  notices: [],
  sections: [],
} as unknown as ReportDocumentView;

function bootScreen() {
  vi.mocked(getReportCatalogue).mockResolvedValue(CATALOGUE);
  vi.mocked(listReportRuns).mockResolvedValue({ runs: [], permissions: {} as never });
  render(<EstateReportsScreen />);
  return screen.findByText("Templates");
}

describe("Estate reports preview", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("Preview calls previewReport once with the template request and renders the document", async () => {
    vi.mocked(previewReport).mockResolvedValue(DOC);
    await bootScreen();

    const card = screen.getByText("Synthetic estate summary", { selector: "div" }).closest("[data-report-template]") as HTMLElement;
    fireEvent.click(within(card).getByRole("button", { name: "Preview Synthetic estate summary" }));

    await waitFor(() => expect(screen.getByText("TPL-SYNTH-01 · Synthetic estate summary", { selector: "h5" })).toBeTruthy());
    expect(previewReport).toHaveBeenCalledTimes(1);
    const [request] = vi.mocked(previewReport).mock.calls[0];
    expect(request).toMatchObject({ template_id: "TPL-SYNTH-01", projection: "MASKED", framework: "ALL" });
    expect(request.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(request.to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(document.querySelector("[data-preview-state='ok']")).not.toBeNull();
    expect(screen.getByText("Read-only: the document is built from stored evidence. Nothing is saved, generated or downloaded.")).toBeTruthy();
  });

  it("a failed preview renders a failure message, not an empty document", async () => {
    vi.mocked(previewReport).mockRejectedValue({ status: 500, body: { reason: "SYNTH_BACKEND_DOWN" } });
    await bootScreen();

    fireEvent.click(screen.getByRole("button", { name: "Preview Synthetic estate summary" }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("The preview for TPL-SYNTH-01 failed: SYNTH_BACKEND_DOWN");
    expect(alert.textContent).toContain("No document is shown.");
    expect(document.querySelector("[data-report-document]")).toBeNull();
    expect(document.querySelector("[data-preview-state='ok']")).toBeNull();
  });

  it("Preview issues no generateReport call and creates no run", async () => {
    vi.mocked(previewReport).mockResolvedValue(DOC);
    await bootScreen();

    fireEvent.click(screen.getByRole("button", { name: "Preview Synthetic estate summary" }));
    await screen.findByText("TPL-SYNTH-01 · Synthetic estate summary", { selector: "h5" });

    expect(generateReport).not.toHaveBeenCalled();
    expect(previewReport).toHaveBeenCalledTimes(1);
  });

  it("the standard view link carries standard=1", async () => {
    vi.mocked(previewReport).mockResolvedValue(DOC);
    await bootScreen();

    fireEvent.click(screen.getByRole("button", { name: "Preview Synthetic estate summary" }));
    await screen.findByText("TPL-SYNTH-01 · Synthetic estate summary", { selector: "h5" });

    const link = screen.getByRole("link", { name: "Open in standard view" });
    expect(link.getAttribute("href")).toContain("standard=1");
    expect(link.getAttribute("href")).toContain("screen=reports");
  });

  it("a refused template offers no Preview action", async () => {
    await bootScreen();
    const refused = document.querySelector("[data-report-template='TPL-SYNTH-02']") as HTMLElement;
    expect(within(refused).queryByRole("button", { name: /Preview/ })).toBeNull();
  });
});
