import { describe, expect, it } from "vitest";
import type { JobEventView } from "../src/auth/adminApi";
import {
  buildDossierJobs,
  DOSSIER_JOBS_CAP,
  DOSSIER_JOBS_FAILED_TEXT,
  DOSSIER_JOBS_NOT_EVALUABLE_TEXT,
  DOSSIER_JOBS_STANDARD_HREF,
  DOSSIER_JOBS_TOTAL_UNKNOWN_TEXT,
  dossierJobsStandardHref,
  type DossierJobsRead,
} from "../src/estate/estateDossierJobsModel";

// Synthetic jobs only (FW-TANGO-04 style); job ids are opaque and never parsed here.
function job(n: number, overrides: Partial<JobEventView> = {}): JobEventView {
  return {
    job_id: `job-synthetic-${n}`,
    job_type: "CONFIG_SNAPSHOT",
    target_device_id: "dev-tango-04",
    state: "FINISHED",
    outcome: null,
    submitted_at: `2026-11-2${(n % 3) + 1}T08:00:00.000Z`,
    ...overrides,
  } as JobEventView;
}

function page(count: number): readonly JobEventView[] {
  return Array.from({ length: count }, (_, i) => job(i + 1));
}

describe("buildDossierJobs: cluster unit", () => {
  it("is not evaluable with no rows, even when a read is present", () => {
    const read: DossierJobsRead = { ok: true, items: page(2), total: 2 };
    expect(buildDossierJobs(read, true)).toEqual({ status: "not_evaluable" });
    expect(buildDossierJobs(null, true)).toEqual({ status: "not_evaluable" });
  });

  it("exposes the not-evaluable text constant", () => {
    expect(DOSSIER_JOBS_NOT_EVALUABLE_TEXT).toBe("Not evaluable for a cluster unit");
  });
});

describe("buildDossierJobs: failed reads", () => {
  it("a null read is failed with no rows", () => {
    expect(buildDossierJobs(null, false)).toEqual({ status: "failed" });
  });

  it("a read that reports ok:false is failed, never an empty OK list", () => {
    const model = buildDossierJobs({ ok: false }, false);
    expect(model).toEqual({ status: "failed" });
    expect(model.status).not.toBe("ok");
  });

  it("a payload without an item list is failed", () => {
    const broken = { ok: true, items: undefined } as unknown as DossierJobsRead;
    expect(buildDossierJobs(broken, false)).toEqual({ status: "failed" });
  });

  it("exposes the failed text constant", () => {
    expect(DOSSIER_JOBS_FAILED_TEXT).toBe("Recent jobs could not be read");
  });
});

describe("buildDossierJobs: cap and remainder", () => {
  it("caps rows at DOSSIER_JOBS_CAP and states the remainder from the total", () => {
    expect(DOSSIER_JOBS_CAP).toBe(5);
    const model = buildDossierJobs({ ok: true, items: page(5), total: 12 }, false);
    expect(model.status).toBe("ok");
    if (model.status !== "ok") return;
    expect(model.rows).toHaveLength(DOSSIER_JOBS_CAP);
    // 12 jobs exist, 5 listed: the remainder is 7, not silently dropped.
    expect(model.remainder).toBe(7);
  });

  it("drops extra items beyond the cap and counts them in the remainder", () => {
    const model = buildDossierJobs({ ok: true, items: page(8), total: 8 }, false);
    if (model.status !== "ok") throw new Error("expected ok");
    expect(model.rows).toHaveLength(5);
    expect(model.remainder).toBe(3);
  });

  it("reports a remainder of 0 when the whole page is listed", () => {
    const model = buildDossierJobs({ ok: true, items: page(3), total: 3 }, false);
    if (model.status !== "ok") throw new Error("expected ok");
    expect(model.rows).toHaveLength(3);
    expect(model.remainder).toBe(0);
  });

  it("an empty page with a zero total is OK with no rows and a zero remainder", () => {
    const model = buildDossierJobs({ ok: true, items: [], total: 0 }, false);
    expect(model).toEqual({ status: "ok", rows: [], remainder: 0 });
  });
});

describe("buildDossierJobs: unknown total", () => {
  it("a missing total gives a null remainder, not a number", () => {
    const model = buildDossierJobs({ ok: true, items: page(2) }, false);
    if (model.status !== "ok") throw new Error("expected ok");
    expect(model.rows).toHaveLength(2);
    expect(model.remainder).toBeNull();
  });

  it("a non-finite total gives a null remainder", () => {
    const nan = buildDossierJobs({ ok: true, items: page(2), total: Number.NaN }, false);
    const inf = buildDossierJobs({ ok: true, items: page(2), total: Number.POSITIVE_INFINITY }, false);
    expect(nan).toMatchObject({ status: "ok", remainder: null });
    expect(inf).toMatchObject({ status: "ok", remainder: null });
  });

  it("a total smaller than the listed rows is treated as unknown, not negative", () => {
    const model = buildDossierJobs({ ok: true, items: page(4), total: 2 }, false);
    expect(model).toMatchObject({ status: "ok", remainder: null });
  });

  it("the total-unknown text carries no digits, so no total is implied", () => {
    expect(DOSSIER_JOBS_TOTAL_UNKNOWN_TEXT).toBe(
      "Total jobs UNKNOWN; more jobs may exist beyond the listed rows",
    );
    expect(DOSSIER_JOBS_TOTAL_UNKNOWN_TEXT).not.toMatch(/\d/);
  });
});

describe("buildDossierJobs: row fields", () => {
  it("blank or missing job_type and state become UNKNOWN; missing submitted_at becomes null", () => {
    const items = [
      job(1, { job_type: "   ", state: "" }),
      job(2, { job_type: undefined as unknown as string, state: undefined as unknown as string, submitted_at: "  " }),
    ];
    const model = buildDossierJobs({ ok: true, items, total: 2 }, false);
    if (model.status !== "ok") throw new Error("expected ok");
    expect(model.rows[0]).toEqual({ jobType: "UNKNOWN", state: "UNKNOWN", submittedAt: items[0].submitted_at });
    expect(model.rows[1]).toEqual({ jobType: "UNKNOWN", state: "UNKNOWN", submittedAt: null });
  });
});

describe("dossierJobsStandardHref", () => {
  it("returns the exported standard-view href", () => {
    expect(dossierJobsStandardHref()).toBe(DOSSIER_JOBS_STANDARD_HREF);
    expect(dossierJobsStandardHref()).toBe("?screen=operations&tab=jobs&standard=1");
  });
});
