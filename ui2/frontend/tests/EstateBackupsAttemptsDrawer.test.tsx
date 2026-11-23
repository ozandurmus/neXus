import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { listJobs, type JobEventView } from "../src/auth/adminApi";
import { EstateBackupsAttemptsDrawer, attemptOutcome } from "../src/estate/EstateBackupsAttemptsDrawer";
import { relativeAge } from "../src/shell/time";

// Synthetic data only: FW-TANGO-04 style names and 192.0.2.x addresses. No real hosts, serials or users.
vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, listJobs: vi.fn() };
});

const UNIT = { deviceId: "dev-tango-04", name: "FW-TANGO-04" };
const NOW = Date.now();
const HOUR = 3_600_000;

function attempt(i: number, state: string, submittedMsAgo: number, reason: string | null = null): JobEventView {
  return {
    job_id: `job-tango-04-${i}`,
    job_type: "backup",
    state,
    terminal_reason: reason,
    submitted_at: new Date(NOW - submittedMsAgo).toISOString(),
    target_device_id: UNIT.deviceId,
    has_transcript: false,
  } as unknown as JobEventView;
}

function stubPage(items: JobEventView[], total = items.length) {
  vi.mocked(listJobs).mockResolvedValue({ items, total, page: 1, page_size: 10 } as never);
}

function renderDrawer() {
  return render(<EstateBackupsAttemptsDrawer unit={UNIT} onClose={vi.fn()} />);
}

beforeEach(() => {
  vi.mocked(listJobs).mockReset();
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Estate Backups attempts drawer", () => {
  it("lists the attempts returned by listJobs with StateBadge and the attemptWords outcome words", async () => {
    stubPage([
      attempt(1, "COMPLETED", 2 * HOUR),
      attempt(2, "FAILED", 5 * HOUR, "Credential rejected by device"),
    ]);
    renderDrawer();

    const rows = await screen.findAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("Completed")).toBeInTheDocument();
    expect(rows[0].querySelector('[data-state-badge="ok"]')).not.toBeNull();
    expect(within(rows[1]).getByText("Failed")).toBeInTheDocument();
    expect(rows[1].querySelector('[data-state-badge="act"]')).not.toBeNull();
    expect(screen.getByText("Newest of the 2 attempts shown:")).toBeInTheDocument();
    expect(listJobs).toHaveBeenCalledWith({ device_id: UNIT.deviceId, job_type: "backup", page: 1, page_size: 10 });
  });

  it("prints singular '1 attempt' when one attempt is returned", async () => {
    stubPage([attempt(1, "COMPLETED", 2 * HOUR)], 1);
    renderDrawer();

    const rows = await screen.findAllByRole("listitem");
    expect(rows).toHaveLength(1);
    expect(screen.getByText("Newest of the 1 attempt shown:")).toBeInTheDocument();
    expect(screen.queryByText(/1 attempts/)).toBeNull();
  });

  it("renders an attempt with an unknown outcome as UNKNOWN with a StateBadge, never OK or Fine", async () => {
    stubPage([
      attempt(1, "OUTCOME_UNKNOWN", HOUR),
      attempt(2, "", 3 * HOUR),
      attempt(3, "COMPLETED", 6 * HOUR),
    ]);
    renderDrawer();

    const rows = await screen.findAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(rows[0].querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(rows[1].querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(rows[2].querySelector('[data-state-badge="ok"]')).not.toBeNull();
    expect(screen.getAllByText("UNKNOWN")).toHaveLength(2);
    expect(screen.queryByText(/^(OK|Fine)$/i)).toBeNull();
    expect(screen.getByText("Completed")).toBeInTheDocument();
  });

  it("caps the list at ten rows and prints '+2 more' for twelve attempts", async () => {
    const items = Array.from({ length: 12 }, (_, i) => attempt(i + 1, "COMPLETED", (i + 1) * HOUR));
    stubPage(items, 12);
    renderDrawer();

    const more = await screen.findByTestId("attempts-drawer-more");
    expect(more).toHaveTextContent("+2 more attempts");
    expect(screen.queryByTestId("attempts-drawer-total-unknown")).toBeNull();
    expect(document.querySelectorAll("[data-attempt-row]")).toHaveLength(10);
  });

  it("renders attempts-drawer-total-unknown when total is missing or non-finite instead of hiding unknown overflow", async () => {
    vi.mocked(listJobs).mockResolvedValueOnce({
      items: [attempt(1, "COMPLETED", 2 * HOUR), attempt(2, "FAILED", 5 * HOUR)],
      total: undefined as never,
      page: 1,
      page_size: 10,
    } as never);
    const { unmount } = renderDrawer();

    const notice = await screen.findByTestId("attempts-drawer-total-unknown");
    expect(notice).toHaveTextContent("Total attempts UNKNOWN; more attempts may exist beyond the listed rows");
    expect(screen.queryByTestId("attempts-drawer-more")).toBeNull();
    unmount();

    vi.mocked(listJobs).mockResolvedValueOnce({
      items: [attempt(1, "COMPLETED", 2 * HOUR), attempt(2, "FAILED", 5 * HOUR)],
      total: Number.NaN as never,
      page: 1,
      page_size: 10,
    } as never);
    renderDrawer();

    expect(await screen.findByTestId("attempts-drawer-total-unknown")).toHaveTextContent("Total attempts UNKNOWN");
    expect(screen.queryByTestId("attempts-drawer-more")).toBeNull();
  });

  it("shows visible text while loading and renders UNKNOWN badge and a0 stamp when the read fails", async () => {
    vi.mocked(listJobs).mockReturnValueOnce(new Promise(() => {}));
    const { unmount } = renderDrawer();
    expect(await screen.findByText("Loading backup attempts…")).toBeInTheDocument();
    unmount();

    vi.mocked(listJobs).mockRejectedValueOnce(new Error("read failed"));
    renderDrawer();
    expect(await screen.findByText("Backup attempts could not be loaded.")).toBeInTheDocument();
    expect(document.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(document.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();
    expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThanOrEqual(1);
    await waitFor(() => expect(screen.queryByText("Loading backup attempts…")).toBeNull());
  });

  it("renders UNKNOWN and a0 evidence stamp without throwing when listJobs resolves with a malformed payload", async () => {
    vi.mocked(listJobs).mockResolvedValueOnce(null as never);
    const { unmount } = renderDrawer();
    expect(await screen.findByText("Backup attempts could not be loaded.")).toBeInTheDocument();
    expect(document.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(document.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();
    expect(screen.queryByText("No backup attempts on record.")).toBeNull();
    unmount();

    vi.mocked(listJobs).mockResolvedValueOnce({ items: "bad" } as never);
    renderDrawer();
    expect(await screen.findByText("Backup attempts could not be loaded.")).toBeInTheDocument();
    expect(document.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(document.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();
    expect(screen.queryByText("No backup attempts on record.")).toBeNull();
  });

  it("the evidence stamp carries the age of the newest attempt shown and guards unparseable submitted_at", async () => {
    const unparseable = { ...attempt(4, "CANCELLED", 0), submitted_at: "not-a-timestamp" };
    stubPage([
      attempt(1, "FAILED", 9 * HOUR, "Credential rejected by device"),
      attempt(2, "COMPLETED", 3 * HOUR),
      attempt(3, "COMPLETED", 20 * HOUR),
      unparseable,
    ]);
    renderDrawer();

    const rows = await screen.findAllByRole("listitem");
    expect(rows).toHaveLength(4);
    expect(rows[3].querySelector('[data-state-badge="na"]')).not.toBeNull();
    expect(within(rows[3]).getByTitle("No submission time recorded")).toHaveTextContent("UNKNOWN");

    const stamp = document.querySelector("[data-evidence-stamp]");
    expect(stamp).not.toBeNull();
    const newestAge = relativeAge(new Date(NOW - 3 * HOUR).toISOString(), new Date());
    expect(stamp).toHaveAttribute("title", expect.stringContaining(newestAge));
    expect(stamp).toHaveAttribute("title", expect.stringContaining("newest attempt shown"));
  });

  it("maps attemptOutcome to BadgeState for ok, bad, warn, unknown, and neutral outcomes", () => {
    expect(attemptOutcome(attempt(1, "COMPLETED", HOUR)).badgeState).toBe("ok");
    expect(attemptOutcome(attempt(2, "FAILED", HOUR)).badgeState).toBe("act");
    expect(attemptOutcome(attempt(3, "COMPLETED", HOUR, "partial: stored without running-config.txt")).badgeState).toBe("watch");
    expect(attemptOutcome(attempt(4, "OUTCOME_UNKNOWN", HOUR)).badgeState).toBe("unk");
    expect(attemptOutcome(attempt(5, "CANCELLED", HOUR)).badgeState).toBe("na");
  });
});
