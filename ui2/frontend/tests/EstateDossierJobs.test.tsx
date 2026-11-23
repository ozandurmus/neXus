import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { listJobs, type JobEventView, type JobPageView } from "../src/auth/adminApi";
import { EstateDossierJobsBlock } from "../src/estate/EstateDossierJobsBlock";
import {
  buildDossierJobs,
  DOSSIER_JOBS_CAP,
  DOSSIER_JOBS_FAILED_TEXT,
  DOSSIER_JOBS_NOT_EVALUABLE_TEXT,
  DOSSIER_JOBS_STANDARD_HREF,
} from "../src/estate/estateDossierJobsModel";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, listJobs: vi.fn() };
});

const mockedList = vi.mocked(listJobs);

// Synthetic identifiers only (FW-TANGO-04 style names). Job ids are opaque and never parsed.
const DEVICE = "dev-tango-04";

function job(n: number, overrides: Partial<JobEventView> = {}): JobEventView {
  return {
    job_id: `job-synthetic-${n}`,
    job_type: n % 2 === 0 ? "CERTIFICATE_READ" : "CONFIG_SNAPSHOT",
    target_device_id: DEVICE,
    state: n % 2 === 0 ? "FINISHED" : "FAILED",
    outcome: null,
    submitted_at: new Date(Date.now() - n * 3_600_000).toISOString(),
    has_transcript: false,
    ...overrides,
  } as JobEventView;
}

function page(items: JobEventView[], total?: number): JobPageView {
  return { items, total: total as number, page: 1, page_size: DOSSIER_JOBS_CAP };
}

beforeEach(() => {
  mockedList.mockReset();
});

describe("EstateDossierJobsBlock", () => {
  it("renders type and state for up to five jobs and prints the +N more remainder", async () => {
    const five = Array.from({ length: 5 }, (_, i) => job(i + 1));
    mockedList.mockResolvedValue(page(five, 9));
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    await waitFor(() => expect(screen.getByTestId("dossier-jobs")).toBeTruthy());
    expect(screen.getAllByText("CONFIG_SNAPSHOT")).toHaveLength(3);
    expect(screen.getAllByText("CERTIFICATE_READ")).toHaveLength(2);
    expect(screen.getAllByText("FAILED")).toHaveLength(3);
    expect(screen.getByTestId("dossier-jobs-more").textContent).toBe("+4 more jobs");
    expect(screen.queryByTestId("dossier-jobs-total-unknown")).toBeNull();
    expect(mockedList).toHaveBeenCalledWith({ device_id: DEVICE, page_size: DOSSIER_JOBS_CAP });
  });

  it("prints no remainder when the page holds every job", async () => {
    mockedList.mockResolvedValue(page([job(1), job(2)], 2));
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    await waitFor(() => expect(screen.getByTestId("dossier-jobs")).toBeTruthy());
    expect(screen.queryByTestId("dossier-jobs-more")).toBeNull();
    expect(screen.queryByTestId("dossier-jobs-total-unknown")).toBeNull();
  });

  it("renders an UNKNOWN total notice instead of hiding overflow when total is omitted or non-finite", async () => {
    const five = Array.from({ length: 5 }, (_, i) => job(i + 1));
    mockedList.mockResolvedValue(page(five, undefined));
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    await waitFor(() => expect(screen.getByTestId("dossier-jobs")).toBeTruthy());
    const notice = screen.getByTestId("dossier-jobs-total-unknown");
    expect(notice.textContent).toContain("UNKNOWN");
    expect(screen.queryByTestId("dossier-jobs-more")).toBeNull();
  });

  it("renders UNKNOWN for missing or blank job_type and state", async () => {
    mockedList.mockResolvedValue(
      page(
        [
          job(1, { job_type: "" as unknown as string, state: "   " as unknown as string }),
          job(2, { job_type: undefined as unknown as string, state: null as unknown as string }),
        ],
        2,
      ),
    );
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    await waitFor(() => expect(screen.getByTestId("dossier-jobs")).toBeTruthy());
    expect(screen.getAllByText("UNKNOWN")).toHaveLength(4);
  });

  it("says the read could not be read with an UNKNOWN badge and a0 stamp, never No jobs or a1, when the call is rejected", async () => {
    mockedList.mockRejectedValue(new Error("synthetic failure"));
    const { container } = render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    await waitFor(() => expect(screen.getByTestId("dossier-jobs-failed")).toBeTruthy());
    expect(screen.getByText(/could not be read/)).toBeTruthy();
    expect(screen.queryByText(/No jobs/i)).toBeNull();
    expect(screen.queryByTestId("dossier-jobs")).toBeNull();
    const failedBox = screen.getByTestId("dossier-jobs-failed");
    expect(failedBox.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(failedBox.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();
    expect(container.querySelector('[data-evidence-stamp="a1"]')).toBeNull();
  });

  it("is not evaluable for a cluster unit and makes no listJobs call", () => {
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit />);
    expect(screen.getByText(DOSSIER_JOBS_NOT_EVALUABLE_TEXT)).toBeTruthy();
    expect(mockedList).not.toHaveBeenCalled();
  });

  it("links to the unfiltered standard job log carrying standard=1 and tab=jobs without state=FAILED", async () => {
    mockedList.mockResolvedValue(page([], 0));
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    const link = await screen.findByText("Open job log (standard view)");
    const href = link.closest("a")?.getAttribute("href") ?? "";
    expect(href).toBe(DOSSIER_JOBS_STANDARD_HREF);
    expect(href).toContain("standard=1");
    expect(href).toContain("tab=jobs");
    expect(href).not.toContain("state=FAILED");
  });
});

describe("buildDossierJobs", () => {
  it("caps rows at five and counts the remainder", () => {
    const items = Array.from({ length: 5 }, (_, i) => job(i + 1));
    const m = buildDossierJobs({ ok: true, items, total: 12 }, false);
    expect(m.status).toBe("ok");
    if (m.status !== "ok") return;
    expect(m.rows).toHaveLength(5);
    expect(m.remainder).toBe(7);
  });

  it("returns remainder: null when total is omitted, non-finite, or smaller than shown rows", () => {
    const omitted = buildDossierJobs({ ok: true, items: [job(1)] }, false);
    expect(omitted.status === "ok" && omitted.remainder).toBeNull();

    const nonFinite = buildDossierJobs({ ok: true, items: [job(1)], total: Number.NaN }, false);
    expect(nonFinite.status === "ok" && nonFinite.remainder).toBeNull();

    const smaller = buildDossierJobs({ ok: true, items: [job(1), job(2)], total: 1 }, false);
    expect(smaller.status === "ok" && smaller.remainder).toBeNull();
  });

  it("maps missing or blank job_type and state to UNKNOWN", () => {
    const m = buildDossierJobs(
      {
        ok: true,
        items: [
          job(1, { job_type: "" as unknown as string, state: "  " as unknown as string }),
          job(2, { job_type: undefined as unknown as string, state: null as unknown as string }),
        ],
        total: 2,
      },
      false,
    );
    expect(m.status).toBe("ok");
    if (m.status !== "ok") return;
    expect(m.rows[0].jobType).toBe("UNKNOWN");
    expect(m.rows[0].state).toBe("UNKNOWN");
    expect(m.rows[1].jobType).toBe("UNKNOWN");
    expect(m.rows[1].state).toBe("UNKNOWN");
  });

  it("maps a failed or malformed read to failed, and a cluster unit to not evaluable", () => {
    expect(buildDossierJobs({ ok: false }, false)).toEqual({ status: "failed" });
    expect(buildDossierJobs(null, false)).toEqual({ status: "failed" });
    expect(buildDossierJobs({ ok: true, items: undefined as unknown as readonly JobEventView[] }, false)).toEqual({
      status: "failed",
    });
    expect(buildDossierJobs({ ok: true, items: [] }, true)).toEqual({ status: "not_evaluable" });
    expect(DOSSIER_JOBS_FAILED_TEXT).toBe("Recent jobs could not be read");
  });
});
