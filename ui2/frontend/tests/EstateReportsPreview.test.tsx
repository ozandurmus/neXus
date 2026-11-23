import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  generateReport,
  getReportCatalogue,
  getReportRun,
  listReportRuns,
  previewReport,
  type ReportCatalogue,
  type ReportRunView,
} from "../src/auth/adminApi";
import { EstateReportsScreen } from "../src/estate/EstateReportsScreen";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getReportCatalogue: vi.fn(),
    getReportRun: vi.fn(),
    listReportRuns: vi.fn(),
    previewReport: vi.fn(),
    generateReport: vi.fn(),
  };
});

const CATALOGUE: ReportCatalogue = {
  templates: [
    {
      template_id: "TPL-SYNTH-01",
      title: "FW-TANGO-04 readiness summary",
      audience: "Operations",
      question: "Is the synthetic estate healthy?",
      formats: ["PDF", "CSV"],
      framework_parameter: true,
      missing_reads: [],
    },
    {
      template_id: "TPL-SYNTH-02",
      title: "FW-JULIET-06 backup posture",
      audience: "Resilience",
      question: "Are synthetic backups current?",
      formats: ["PDF"],
      framework_parameter: false,
      missing_reads: [],
    },
    {
      template_id: "TPL-SYNTH-03",
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

const SYNTH_RUN: ReportRunView = {
  run_id: "run-tango-04",
  template_id: "TPL-SYNTH-01",
  title: "FW-TANGO-04 readiness summary",
  state: "SUCCEEDED",
  projection: "MASKED",
  format: "PDF",
  parameters: {},
  data_as_of: { planes: [] },
  plaintext_bytes: 128,
  error_code: null,
  requested_at: "2026-11-22T10:00:00.000Z",
  finished_at: "2026-11-22T10:01:00.000Z",
  expires_at: "2026-11-29T10:00:00.000Z",
  downloadable: true,
  mine: true,
};

function bootScreen(runs: readonly ReportRunView[] = []) {
  vi.mocked(getReportCatalogue).mockResolvedValue(CATALOGUE);
  vi.mocked(listReportRuns).mockResolvedValue({ runs: [...runs], permissions: {} as never });
  vi.mocked(getReportRun).mockResolvedValue(SYNTH_RUN);
  render(<EstateReportsScreen />);
  return screen.findByText("Templates");
}

describe("Estate reports preview link to standard view", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it("links each available template row to the standard view with standard=1 and makes no previewReport call", async () => {
    await bootScreen();

    for (const templateId of ["TPL-SYNTH-01", "TPL-SYNTH-02"]) {
      const card = document.querySelector(`[data-report-template='${templateId}']`) as HTMLElement;
      expect(card).not.toBeNull();

      const previewLink = within(card).getByRole("link", { name: "Preview in standard view" });
      expect(previewLink.getAttribute("href")).toBe("?screen=reports&standard=1");
      expect(previewLink.getAttribute("href")).toContain("standard=1");

      const generateLink = within(card).getByRole("link", { name: "Generate in standard Reports" });
      expect(generateLink.getAttribute("href")).toBe("?screen=reports&standard=1");
      expect(generateLink.getAttribute("href")).toContain("standard=1");
    }

    expect(previewReport).not.toHaveBeenCalled();
    expect(generateReport).not.toHaveBeenCalled();
    expect(document.querySelector("[data-preview-state]")).toBeNull();
  });

  it("contains no Preview button and no button that calls a POST", async () => {
    await bootScreen([SYNTH_RUN]);

    expect(screen.queryByRole("button", { name: /Preview/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /Generate/i })).toBeNull();

    for (const card of Array.from(document.querySelectorAll("[data-report-template]"))) {
      expect(card.querySelectorAll("button")).toHaveLength(0);
    }

    for (const button of screen.queryAllByRole("button")) {
      fireEvent.click(button);
    }
    await waitFor(() => expect(getReportRun).toHaveBeenCalledTimes(1));

    expect(previewReport).not.toHaveBeenCalled();
    expect(generateReport).not.toHaveBeenCalled();
  });

  it("a refused template offers no preview or generate link or button", async () => {
    await bootScreen();
    const refused = document.querySelector("[data-report-template='TPL-SYNTH-03']") as HTMLElement;
    expect(refused).not.toBeNull();
    expect(within(refused).queryByRole("link", { name: /Preview|Generate/i })).toBeNull();
    expect(within(refused).queryByRole("button")).toBeNull();
    expect(previewReport).not.toHaveBeenCalled();
  });
});
