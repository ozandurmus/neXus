import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { listJobs, type JobEventView, type JobPageView } from "../src/auth/adminApi";
import { EstateDossierJobsBlock } from "../src/estate/EstateDossierJobsBlock";
import {
  buildDossierJobs,
  DOSSIER_JOBS_CAP,
  DOSSIER_JOBS_FAILED_TEXT,
  DOSSIER_JOBS_NOT_EVALUABLE_TEXT,
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

function page(items: JobEventView[], total: number): JobPageView {
  return { items, total, page: 1, page_size: DOSSIER_JOBS_CAP };
}

beforeEach(() => {
  mockedList.mockReset();
});

describe("EstateDossierJobsBlock", () => {
  it("renders type and state for up to five jobs and prints the +N more remainder", async () => {
    const six = Array.from({ length: 5 }, (_, i) => job(i + 1));
    mockedList.mockResolvedValue(page(six, 9));
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    await waitFor(() => expect(screen.getByTestId("dossier-jobs")).toBeTruthy());
    expect(screen.getAllByText("CONFIG_SNAPSHOT")).toHaveLength(3);
    expect(screen.getAllByText("CERTIFICATE_READ")).toHaveLength(2);
    expect(screen.getAllByText("FAILED")).toHaveLength(3);
    expect(screen.getByTestId("dossier-jobs-more").textContent).toBe("+4 more jobs");
    expect(mockedList).toHaveBeenCalledWith({ device_id: DEVICE, page_size: DOSSIER_JOBS_CAP });
  });

  it("prints no remainder when the page holds every job", async () => {
    mockedList.mockResolvedValue(page([job(1), job(2)], 2));
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    await waitFor(() => expect(screen.getByTestId("dossier-jobs")).toBeTruthy());
    expect(screen.queryByTestId("dossier-jobs-more")).toBeNull();
  });

  it("says the read could not be read, never No jobs, when the call is rejected", async () => {
    mockedList.mockRejectedValue(new Error("synthetic failure"));
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    await waitFor(() => expect(screen.getByTestId("dossier-jobs-failed")).toBeTruthy());
    expect(screen.getByText(/could not be read/)).toBeTruthy();
    expect(screen.queryByText(/No jobs/i)).toBeNull();
    expect(screen.queryByTestId("dossier-jobs")).toBeNull();
  });

  it("is not evaluable for a cluster unit and makes no listJobs call", () => {
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit />);
    expect(screen.getByText(DOSSIER_JOBS_NOT_EVALUABLE_TEXT)).toBeTruthy();
    expect(mockedList).not.toHaveBeenCalled();
  });

  it("links to the standard job view carrying standard=1", async () => {
    mockedList.mockResolvedValue(page([], 0));
    render(<EstateDossierJobsBlock deviceId={DEVICE} clusterUnit={false} />);
    const link = await screen.findByText("Open job log (standard view)");
    expect(link.closest("a")?.getAttribute("href")).toContain("standard=1");
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

  it("uses the shown row count when total is not reported", () => {
    const m = buildDossierJobs({ ok: true, items: [job(1)] }, false);
    expect(m.status === "ok" && m.remainder).toBe(0);
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
