import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  getJobStats,
  getScheduledJobs,
  getSystemHealth,
  listAuditEvents,
  listDevices,
  listLocalIdentities,
  listSessions,
  type JobStatsView,
} from "../src/auth/adminApi";
import { mapJobQueue, mapJobQueueFigures, JOB_QUEUE_FAILED_TEXT } from "../src/estate/estateJobQueueModel";
import { EstateSystemScreen } from "../src/estate/EstateSystemScreen";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getJobStats: vi.fn(),
    getScheduledJobs: vi.fn(),
    getSystemHealth: vi.fn(),
    listAuditEvents: vi.fn(),
    listDevices: vi.fn(),
    listLocalIdentities: vi.fn(),
    listSessions: vi.fn(),
  };
});

// Synthetic values only; the counts are arbitrary test numbers, not production figures.
const AT = "2026-11-22T08:00:00.000Z";
const FAILED_HREF = "?screen=operations&tab=jobs&state=FAILED&since_hours=24&standard=1";

function fullStats(): JobStatsView {
  return { total: 900, total_24h: 40, completed_24h: 30, failed_24h: 3, outcome_unknown_24h: 2, running: 1 };
}

/** The value cell that sits beside a figure's label. */
function valueFor(label: string): string | null | undefined {
  const dt = screen.getByText(label, { exact: false, selector: "dt" });
  return dt.parentElement?.querySelector("dd")?.textContent;
}

function mockOtherReads() {
  vi.mocked(getScheduledJobs).mockResolvedValue({
    master_enabled: true, master_env: "SYNTHETIC_MASTER", can_write: false, masked: true, business_hours: null, families: [],
  } as never);
  vi.mocked(listDevices).mockResolvedValue({ devices: [] } as never);
  vi.mocked(getSystemHealth).mockRejectedValue(new Error("synthetic"));
  vi.mocked(listAuditEvents).mockResolvedValue({ events: [] } as never);
  vi.mocked(listLocalIdentities).mockResolvedValue({ identities: [] } as never);
  vi.mocked(listSessions).mockResolvedValue({ sessions: [] } as never);
}

describe("Estate System screen: Job queue section", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockOtherReads();
  });

  it("renders the four figures with their windows, and the failed link keeps standard=1", async () => {
    vi.mocked(getJobStats).mockResolvedValue(fullStats());

    render(<EstateSystemScreen />);

    expect(await screen.findByText("3 of 40")).toBeTruthy();
    expect(screen.getByText("Failed in the last 24 h, out of all jobs in the last 24 h", { selector: "dt", exact: false })).toBeTruthy();
    expect(screen.getByText("Completed in the last 24 h", { selector: "dt", exact: false })).toBeTruthy();
    expect(screen.getByText("Outcome unknown in the last 24 h", { selector: "dt", exact: false })).toBeTruthy();
    expect(screen.getByText("Running now (jobs in flight at the read)", { selector: "dt", exact: false })).toBeTruthy();
    expect(valueFor("Completed in the last 24 h")).toBe("30");
    expect(valueFor("Outcome unknown in the last 24 h")).toBe("2");
    expect(valueFor("Running now")).toBe("1");

    const link = screen.getByRole("link", { name: /Open failed jobs \(standard view\)/ });
    expect(link.getAttribute("href")).toBe(FAILED_HREF);
    expect(link.getAttribute("href")).toContain("standard=1");
  });

  it("shows 'not reported' for a missing outcome_unknown_24h, never 0", async () => {
    const { outcome_unknown_24h: _omitted, ...withoutOutcome } = fullStats();
    vi.mocked(getJobStats).mockResolvedValue(withoutOutcome as JobStatsView);

    render(<EstateSystemScreen />);

    await waitFor(() => expect(valueFor("Outcome unknown in the last 24 h")).toBe("not reported"));
    expect(valueFor("Completed in the last 24 h")).toBe("30");
  });

  it("shows 'could not be read' and no count when the read is rejected", async () => {
    vi.mocked(getJobStats).mockRejectedValue(new Error("synthetic"));

    render(<EstateSystemScreen />);

    expect(await screen.findByText(JOB_QUEUE_FAILED_TEXT)).toBeTruthy();
    expect(screen.queryByText("Completed in the last 24 h", { selector: "dt", exact: false })).toBeNull();
    expect(screen.queryByText("Running now", { selector: "dt", exact: false })).toBeNull();
    const card = screen.getByRole("heading", { name: "Job queue" }).closest("section, div[class], article") ?? document.body;
    expect(card.textContent).not.toMatch(/\b0\b/);
  });
});

describe("estateJobQueueModel", () => {
  it("maps a missing optional field to null (not reported), never 0", () => {
    const { outcome_unknown_24h: _omitted, ...withoutOutcome } = fullStats();
    const figures = mapJobQueueFigures(withoutOutcome as JobStatsView);
    const outcome = figures.find((f) => f.id === "outcome_unknown");
    expect(outcome?.value).toBeNull();
    expect(figures.find((f) => f.id === "failed")?.value).toBe("3 of 40");
  });

  it("maps a failed read to the unreadable state, not to a count", () => {
    const section = mapJobQueue({ read: { state: "failed" }, readAt: AT });
    expect(section).toEqual({ kind: "unreadable", text: JOB_QUEUE_FAILED_TEXT });
  });

  it("is never OK while a figure is not reported", () => {
    const { outcome_unknown_24h: _omitted, ...withoutOutcome } = fullStats();
    const section = mapJobQueue({ read: { state: "ok", value: { ...withoutOutcome, failed_24h: 0 } as JobStatsView }, readAt: AT });
    expect(section.kind === "ok" && section.badge).toBe("unk");
  });
});
