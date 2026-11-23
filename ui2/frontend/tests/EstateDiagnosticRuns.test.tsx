import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { diagnosticHistory, type DiagnosticHistoryRow } from "../src/auth/adminApi";
import { EstateCasesScreen } from "../src/estate/EstateCasesScreen";
import { EstateDiagnosticRuns } from "../src/estate/EstateDiagnosticRuns";
import {
  buildDiagnosticRunsModel,
  DIAGNOSTIC_HISTORY_PAGE_SIZE,
  diagnosticRunStateView,
} from "../src/estate/estateDiagnosticRunsModel";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, diagnosticHistory: vi.fn() };
});

const historyMock = vi.mocked(diagnosticHistory);

const AT_OLD = new Date(Date.now() - 3 * 3_600_000).toISOString();
const AT_NEW = new Date(Date.now() - 30 * 60_000).toISOString();

function makeRun(n: number, overrides: Partial<DiagnosticHistoryRow> = {}): DiagnosticHistoryRow {
  return {
    jobId: `job-diag-${n}`,
    targetDeviceId: `dev-tango-0${(n % 4) + 1}`,
    target: `FW-TANGO-0${(n % 4) + 1}`,
    command: "show high-availability all",
    description: `HA status read #${n}`,
    actor: "SN-0123456789",
    submittedAt: new Date(Date.now() - n * 60_000).toISOString(),
    state: "COMPLETED",
    exitStatus: 0,
    durationMs: 4200,
    ...overrides,
  };
}

describe("EstateDiagnosticRuns", () => {
  beforeEach(() => {
    historyMock.mockReset();
  });

  it("renders diagnostic runs newest first with target, description, actor label, state badge, duration, count line and EvidenceStamp", async () => {
    const older = makeRun(1, {
      jobId: "job-older",
      target: "FW-SIERRA-02",
      description: "Route summary check",
      actor: "system:scheduler",
      submittedAt: AT_OLD,
      state: "COMPLETED",
      durationMs: 12_300,
    });
    const newer = makeRun(2, {
      jobId: "job-newer",
      target: "FW-TANGO-04",
      description: "Cluster sync read",
      actor: "SN-0123456789",
      submittedAt: AT_NEW,
      state: "EXECUTING",
      durationMs: 3_500,
    });
    historyMock.mockResolvedValue({ runs: [older, newer] });

    render(<EstateDiagnosticRuns />);

    const counts = await screen.findByTestId("diagnostic-runs-counts");
    expect(counts).toHaveTextContent("2 diagnostic runs listed");
    expect(counts.querySelector("[data-evidence-stamp]")).not.toBeNull();
    expect(historyMock).toHaveBeenCalledWith("", 0);

    const rows = screen.getAllByTestId("diagnostic-run-row");
    expect(rows).toHaveLength(2);
    // Newest run is rendered first even though older came first in the response array.
    expect(rows[0]).toHaveAttribute("data-job", "job-newer");
    expect(within(rows[0]).getByText("FW-TANGO-04")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Cluster sync read")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Masked actor")).toBeInTheDocument();
    expect(rows[0].querySelector('[data-state-badge="watch"]')).toHaveTextContent("Running");
    expect(within(rows[0]).getByText("3.5 s")).toBeInTheDocument();
    expect(rows[0].querySelector("[data-evidence-stamp]")).not.toBeNull();

    expect(rows[1]).toHaveAttribute("data-job", "job-older");
    expect(within(rows[1]).getByText("FW-SIERRA-02")).toBeInTheDocument();
    expect(within(rows[1]).getByText("Route summary check")).toBeInTheDocument();
    expect(within(rows[1]).getByText(/service · /)).toBeInTheDocument();
    expect(rows[1].querySelector('[data-state-badge="ok"]')).toHaveTextContent("Completed");
    expect(within(rows[1]).getByText("12.3 s")).toBeInTheDocument();
  });

  it("renders OUTCOME_UNKNOWN or an unrecognised run state as UNKNOWN (data-state-badge='unk') and never OK or Fine", async () => {
    historyMock.mockResolvedValue({
      runs: [
        makeRun(1, { jobId: "job-unk-1", state: "OUTCOME_UNKNOWN" }),
        makeRun(2, { jobId: "job-unk-2", state: "MYSTERY_STATE" }),
      ],
    });

    render(<EstateDiagnosticRuns />);

    const rows = await screen.findAllByTestId("diagnostic-run-row");
    expect(rows).toHaveLength(2);
    for (const row of rows) {
      const unkBadge = row.querySelector('[data-state-badge="unk"]');
      expect(unkBadge).not.toBeNull();
      expect(unkBadge).toHaveTextContent("UNKNOWN");
      expect(row.querySelector('[data-state-badge="ok"]')).toBeNull();
      expect(within(row).queryByText(/^(OK|Fine)$/)).toBeNull();
    }

    expect(diagnosticRunStateView("COMPLETED")).toEqual({ state: "ok", word: "Completed" });
    expect(diagnosticRunStateView("CLAIMED")).toEqual({ state: "watch", word: "Running" });
    expect(diagnosticRunStateView("REQUESTED")).toEqual({ state: "watch", word: "Queued" });
    expect(diagnosticRunStateView("EXECUTING")).toEqual({ state: "watch", word: "Running" });
    expect(diagnosticRunStateView("FAILED")).toEqual({ state: "act", word: "Failed" });
    expect(diagnosticRunStateView("REJECTED")).toEqual({ state: "act", word: "Rejected" });
    expect(diagnosticRunStateView("CANCELLED")).toEqual({ state: "na", word: "Cancelled" });
    expect(diagnosticRunStateView("RECONCILED")).toEqual({ state: "na", word: "Reconciled" });
    expect(diagnosticRunStateView("OUTCOME_UNKNOWN")).toEqual({ state: "unk", word: "UNKNOWN" });
    expect(diagnosticRunStateView("")).toEqual({ state: "unk", word: "UNKNOWN" });
  });

  it("caps twelve runs at 10 rows and renders '+2 more diagnostic runs' via MoreFooter", async () => {
    const twelve = Array.from({ length: 12 }, (_, i) => makeRun(i + 1));
    historyMock.mockResolvedValue({ runs: twelve });

    render(<EstateDiagnosticRuns />);

    const rows = await screen.findAllByTestId("diagnostic-run-row");
    expect(rows).toHaveLength(10);
    expect(screen.getByTestId("diagnostic-runs-counts")).toHaveTextContent("12 diagnostic runs listed");
    expect(screen.getByTestId("diagnostic-runs-more")).toHaveTextContent("+2 more diagnostic runs");
  });

  it("renders UNKNOWN on a failed or malformed read and Restricted on a 403 read, never an empty list or OK", async () => {
    historyMock.mockRejectedValueOnce(new Error("upstream unavailable"));
    const { unmount } = render(<EstateDiagnosticRuns />);

    const failed = await screen.findByTestId("diagnostic-runs-failed");
    const secFailed = screen.getByTestId("diagnostic-runs-section");
    expect(secFailed.querySelector('[data-state-badge="unk"]')).toHaveTextContent("UNKNOWN");
    expect(failed).toHaveTextContent("Diagnostic runs could not be read.");
    const failedStamp = failed.querySelector("[data-evidence-stamp]");
    expect(failedStamp).toHaveAttribute("data-evidence-stamp", "a0");
    expect(failedStamp).toHaveTextContent("UNKNOWN");
    expect(secFailed.querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(within(secFailed).queryByText("No diagnostic runs recorded.")).toBeNull();
    expect(within(secFailed).queryByText(/^(OK|Fine)$/)).toBeNull();
    unmount();

    // Malformed payload without a runs array also renders UNKNOWN.
    historyMock.mockResolvedValueOnce({} as unknown as { runs: DiagnosticHistoryRow[] });
    const { unmount: unmountMalformed } = render(<EstateDiagnosticRuns />);
    const malformed = await screen.findByTestId("diagnostic-runs-failed");
    expect(malformed.querySelector("[data-evidence-stamp]")).toHaveAttribute("data-evidence-stamp", "a0");
    expect(screen.getByTestId("diagnostic-runs-section").querySelector('[data-state-badge="unk"]')).not.toBeNull();
    unmountMalformed();

    // 403 restricted read renders StateBadge state="na" word="Restricted".
    historyMock.mockRejectedValueOnce({ status: 403, body: { code: "ACTION_REFUSED" } });
    render(<EstateDiagnosticRuns />);

    const restricted = await screen.findByTestId("diagnostic-runs-restricted");
    const secRestricted = screen.getByTestId("diagnostic-runs-section");
    expect(secRestricted.querySelector('[data-state-badge="na"]')).toHaveTextContent("Restricted");
    expect(restricted).toHaveTextContent("Diagnostic history is restricted for this role.");
    const restrictedStamp = restricted.querySelector("[data-evidence-stamp]");
    expect(restrictedStamp).toHaveAttribute("data-evidence-stamp", "a0");
    expect(restrictedStamp).toHaveTextContent("UNKNOWN");
    expect(secRestricted.querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(within(secRestricted).queryByText("No diagnostic runs recorded.")).toBeNull();
    expect(within(secRestricted).queryByText(/^(OK|Fine)$/)).toBeNull();
  });

  it("renders the empty state and links to ?screen=diagnostics&standard=1 with no button inside the section", async () => {
    historyMock.mockResolvedValue({ runs: [] });
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({}), { status: 404 })));

    render(<EstateCasesScreen />);

    const sec = await screen.findByTestId("diagnostic-runs-section");
    expect(await within(sec).findByTestId("diagnostic-runs-empty")).toHaveTextContent("No diagnostic runs recorded.");
    expect(within(sec).getByTestId("diagnostic-runs-counts")).toHaveTextContent("0 diagnostic runs listed");
    expect(sec.querySelector('[data-state-badge="ok"]')).toBeNull();

    const link = within(sec).getByRole("link", { name: /\(standard view\)/i });
    expect(link).toHaveAttribute("href", "?screen=diagnostics&standard=1");
    expect(within(sec).queryAllByRole("button")).toHaveLength(0);
    expect(sec.querySelectorAll("button")).toHaveLength(0);

    const model = buildDiagnosticRunsModel({ status: "ok", runs: [makeRun(1)], readAt: AT_NEW });
    expect(model.status === "ok" && model.countLine).toBe("1 diagnostic run listed");
    vi.unstubAllGlobals();
  });

  it("stamps the evidence with the history read time, not the age of the newest run", () => {
    const readAt = new Date().toISOString();
    const twoDaysAgo = new Date(Date.now() - 2 * 86_400_000).toISOString();
    const model = buildDiagnosticRunsModel({
      status: "ok",
      runs: [makeRun(1, { submittedAt: twoDaysAgo })],
      readAt,
    });
    expect(model.status === "ok" && model.evidenceAt).toBe(readAt);
    expect(model.status === "ok" && model.evidenceAt).not.toBe(twoDaysAgo);
  });

  it("models failed and restricted reads with evidenceAt null, never the local read instant", () => {
    const readAt = new Date().toISOString();
    expect(buildDiagnosticRunsModel({ status: "failed", readAt })).toMatchObject({ status: "failed", evidenceAt: null });
    expect(buildDiagnosticRunsModel({ status: "restricted", readAt })).toMatchObject({
      status: "restricted",
      evidenceAt: null,
    });
  });

  it("flags a full first page of 50 runs as truncated and never presents it as the exact total", async () => {
    expect(DIAGNOSTIC_HISTORY_PAGE_SIZE).toBe(50);
    const fifty = Array.from({ length: 50 }, (_, i) => makeRun(i + 1));
    historyMock.mockResolvedValue({ runs: fifty });

    const model = buildDiagnosticRunsModel({ status: "ok", runs: fifty, readAt: AT_NEW });
    expect(model.status === "ok" && model.truncated).toBe(true);
    expect(model.status === "ok" && model.countLine).toBe("50 diagnostic runs on first page (more may exist)");

    render(<EstateDiagnosticRuns />);

    const counts = await screen.findByTestId("diagnostic-runs-counts");
    expect(counts).toHaveTextContent("50 diagnostic runs on first page (more may exist)");
    expect(counts).not.toHaveTextContent(/^50 diagnostic runs listed$/);
    expect(screen.getAllByTestId("diagnostic-run-row")).toHaveLength(10);
    expect(screen.getByTestId("diagnostic-runs-more")).toHaveTextContent(
      "+40 more diagnostic runs on this page (more may exist)",
    );
  });

  it("does not flag a 12-run page as truncated", () => {
    const twelve = Array.from({ length: 12 }, (_, i) => makeRun(i + 1));
    const model = buildDiagnosticRunsModel({ status: "ok", runs: twelve, readAt: AT_NEW });
    expect(model.status === "ok" && model.truncated).toBe(false);
    expect(model.status === "ok" && model.countLine).toBe("12 diagnostic runs listed");
  });

  it("renders the EvidenceStamp from the read time when the newest run was submitted two days earlier", async () => {
    const twoDaysAgo = new Date(Date.now() - 2 * 86_400_000).toISOString();
    historyMock.mockResolvedValue({ runs: [makeRun(1, { jobId: "job-old-run", submittedAt: twoDaysAgo })] });

    render(<EstateDiagnosticRuns />);

    const counts = await screen.findByTestId("diagnostic-runs-counts");
    const stamp = counts.querySelector("[data-evidence-stamp]");
    expect(stamp).not.toBeNull();
    // Fresh read: the stamp is the fresh bucket (a1), not the two-day-old submit time (a2/a3/a4).
    expect(stamp).toHaveAttribute("data-evidence-stamp", "a1");
    const row = screen.getByTestId("diagnostic-run-row");
    expect(row.querySelector("[data-evidence-stamp]")).not.toBeNull();
  });
});
