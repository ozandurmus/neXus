import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { listJobs, type JobEventView } from "../src/auth/adminApi";
import { EstateBackupsAttemptsDrawer } from "../src/estate/EstateBackupsAttemptsDrawer";
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
  it("lists the attempts returned by listJobs with the attemptWords outcome words", async () => {
    stubPage([
      attempt(1, "COMPLETED", 2 * HOUR),
      attempt(2, "FAILED", 5 * HOUR, "Credential rejected by device"),
    ]);
    renderDrawer();

    const rows = await screen.findAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("Completed")).toBeInTheDocument();
    expect(within(rows[1]).getByText("Failed")).toBeInTheDocument();
    expect(listJobs).toHaveBeenCalledWith({ device_id: UNIT.deviceId, job_type: "backup", page: 1, page_size: 10 });
  });

  it("renders an attempt with an unknown outcome as UNKNOWN, never OK or Fine", async () => {
    stubPage([
      attempt(1, "OUTCOME_UNKNOWN", HOUR),
      attempt(2, "", 3 * HOUR),
      attempt(3, "COMPLETED", 6 * HOUR),
    ]);
    renderDrawer();

    await screen.findAllByRole("listitem");
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
    expect(document.querySelectorAll("[data-attempt-row]")).toHaveLength(10);
  });

  it("shows visible text while loading and when the read fails", async () => {
    vi.mocked(listJobs).mockReturnValueOnce(new Promise(() => {}));
    const { unmount } = renderDrawer();
    expect(await screen.findByText("Loading backup attempts…")).toBeInTheDocument();
    unmount();

    vi.mocked(listJobs).mockRejectedValueOnce(new Error("read failed"));
    renderDrawer();
    expect(await screen.findByText("Backup attempts could not be loaded.")).toBeInTheDocument();
    expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("Loading backup attempts…")).toBeNull());
  });

  it("the evidence stamp carries the age of the newest attempt shown", async () => {
    stubPage([
      attempt(1, "FAILED", 9 * HOUR, "Credential rejected by device"),
      attempt(2, "COMPLETED", 3 * HOUR),
      attempt(3, "COMPLETED", 20 * HOUR),
    ]);
    renderDrawer();

    await screen.findAllByRole("listitem");
    const stamp = document.querySelector("[data-evidence-stamp]");
    expect(stamp).not.toBeNull();
    const newestAge = relativeAge(new Date(NOW - 3 * HOUR).toISOString(), new Date());
    expect(stamp).toHaveAttribute("title", expect.stringContaining(newestAge));
    expect(stamp).toHaveAttribute("title", expect.stringContaining("newest attempt shown"));
  });
});
