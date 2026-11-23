import { describe, expect, it } from "vitest";
import type { ReportCatalogue, ReportRunView } from "../src/auth/adminApi";
import {
  REPORT_LIST_CAP,
  buildRunRows,
  buildTemplateRows,
  capList,
  evidenceAgeLabel,
  newestEvidenceAt,
  standardReportsHref,
  summariseRuns,
} from "../src/estate/estateReportsModel";

const NOW = new Date("2026-11-22T12:00:00.000Z");
const HOUR = 3_600_000;

function template(id: string, missing: string[] = []) {
  return {
    template_id: id,
    title: `Title ${id}`,
    audience: "Auditor",
    question: `Question for ${id}`,
    formats: ["PDF", "CSV"] as const,
    framework_parameter: false,
    missing_reads: missing,
  };
}

function run(id: string, requestedHoursAgo: number, state: ReportRunView["state"], evidenceHoursAgo: number | null, planes?: ReportRunView["data_as_of"]): ReportRunView {
  const newest = evidenceHoursAgo === null ? null : new Date(NOW.getTime() - evidenceHoursAgo * HOUR).toISOString();
  return {
    run_id: id,
    template_id: "RPT-01",
    title: "Failover readiness",
    state,
    projection: "MASKED",
    format: "PDF",
    parameters: {},
    data_as_of: planes ?? { planes: newest === null ? [] : [{ plane: "failover", newest, oldest: null, covered: 3, total: 4, stale: 1, unit: "pairs" }] },
    plaintext_bytes: null,
    error_code: state === "FAILED" ? "RENDER_FAILED" : null,
    requested_at: new Date(NOW.getTime() - requestedHoursAgo * HOUR).toISOString(),
    finished_at: null,
    expires_at: new Date(NOW.getTime() + 24 * HOUR).toISOString(),
    downloadable: state === "SUCCEEDED",
    mine: true,
  };
}

describe("estate reports list model", () => {
  it("caps lists and reports how many rows were left out", () => {
    const items = Array.from({ length: REPORT_LIST_CAP + 3 }, (_, i) => i);
    const capped = capList(items);
    expect(capped.shown).toHaveLength(REPORT_LIST_CAP);
    expect(capped.more).toBe(3);
    expect(capList([1, 2], 5)).toEqual({ shown: [1, 2], more: 0 });
  });

  it("labels a refused template with the reads it lacks and never calls it available", () => {
    const catalogue: ReportCatalogue = {
      templates: [template("RPT-01"), template("RPT-07", ["packet_capture", "raw_config"])],
      frameworks: [],
      permissions: { masked: true, raw_view: false },
      retention_days: 30,
    };
    const rows = buildTemplateRows(catalogue);
    expect(rows[0]).toMatchObject({ status: "AVAILABLE", missingReads: [] });
    expect(rows[1].status).toBe("REFUSED");
    expect(rows[1].statusText).toBe("Refused: needs read access to packet_capture, raw_config");
    expect(rows[1].formats).toEqual(["PDF", "CSV"]);
  });

  it("states the age of the evidence, not of the request, and shows UNKNOWN when no plane is dated", () => {
    const rows = buildRunRows([run("run-a", 2, "SUCCEEDED", 30), run("run-b", 1, "SUCCEEDED", null)], NOW);
    const byId = Object.fromEntries(rows.map(r => [r.runId, r]));
    expect(byId["run-a"].evidenceAgeLabel).toBe("30 h");
    expect(byId["run-b"].evidenceAgeLabel).toBe("UNKNOWN");
    expect(byId["run-b"].evidenceBucket).toBe("never");
  });

  it("takes the newest plane timestamp as the evidence age of a run", () => {
    const planes = [
      { plane: "failover", newest: new Date(NOW.getTime() - 50 * HOUR).toISOString(), oldest: null, covered: 1, total: 2, stale: 1, unit: "pairs" },
      { plane: "backups", newest: new Date(NOW.getTime() - 5 * 60_000).toISOString(), oldest: null, covered: 2, total: 2, stale: 0, unit: "devices" },
      { plane: "controls", newest: null, oldest: null, covered: 0, total: 2, stale: 0, unit: "devices" },
    ];
    const r = run("run-c", 1, "SUCCEEDED", null, { planes });
    expect(newestEvidenceAt(r)).toBe(planes[1].newest);
    expect(evidenceAgeLabel(newestEvidenceAt(r), NOW)).toBe("5 min");
  });

  it("orders runs newest first and summarises them by state with an exact sentence", () => {
    const runs = [run("old", 48, "SUCCEEDED", 10), run("new", 1, "FAILED", 2), run("mid", 5, "RUNNING", 3)];
    const rows = buildRunRows(runs, NOW);
    expect(rows.map(r => r.runId)).toEqual(["new", "mid", "old"]);
    expect(rows[0]).toMatchObject({ tone: "act", errorCode: "RENDER_FAILED", downloadable: false });
    expect(rows[1].tone).toBe("review");
    expect(summariseRuns(runs)).toEqual({
      total: 3,
      succeeded: 1,
      failed: 1,
      inProgress: 1,
      sentence: "3 report runs listed: 1 succeeded, 1 failed, 1 in progress.",
    });
  });

  it("links generation to the standard Reports screen with standard=1", () => {
    expect(standardReportsHref()).toBe("?screen=reports&standard=1");
  });
});

import {
  REPORT_SECTION_CAP,
  runDocument,
  newestEvidenceAt as newestEvidenceAtRun,
  runStateBadge,
  runStateLabel,
  sectionCountLabel,
} from "../src/estate/estateReportsModel";

describe("estate report run detail helpers", () => {
  it("names the section count and what it counts", () => {
    expect(sectionCountLabel(1)).toBe("1 section");
    expect(sectionCountLabel(0)).toBe("0 sections");
    expect(sectionCountLabel(REPORT_SECTION_CAP + 2)).toBe("14 sections");
  });

  it("never reports an unknown state as OK", () => {
    const unknown = "WEIRD" as ReportRunView["state"];
    expect(runStateLabel(unknown)).toBe("UNKNOWN");
    expect(runStateLabel(null)).toBe("UNKNOWN");
    expect(runStateBadge(unknown)).toBe("unk");
    expect(runStateBadge(undefined)).toBe("unk");
    expect(runStateBadge(null)).toBe("unk");
    expect(runStateBadge("SUCCEEDED")).toBe("ok");
    expect(runStateBadge("FAILED")).toBe("act");
    expect(runStateBadge("QUEUED")).toBe("watch");
    expect(runStateBadge("RUNNING")).toBe("watch");
  });

  it("reads evidence age safely when data_as_of is null, omitted or has no planes array", () => {
    const base = run("r4", 1, "SUCCEEDED", 1);
    expect(newestEvidenceAtRun({ ...base, data_as_of: null } as unknown as ReportRunView)).toBeNull();
    expect(newestEvidenceAtRun({ ...base, data_as_of: undefined } as unknown as ReportRunView)).toBeNull();
    expect(newestEvidenceAtRun({ ...base, data_as_of: {} } as unknown as ReportRunView)).toBeNull();
    expect(newestEvidenceAtRun({ ...base, data_as_of: { planes: "nope" } } as unknown as ReportRunView)).toBeNull();
  });

  it("treats a malformed document as unavailable, not as an empty OK document", () => {
    expect(runDocument(run("r1", 1, "SUCCEEDED", 1)).available).toBe(false);
    const malformed = { ...run("r2", 1, "SUCCEEDED", 1), document: { sections: "nope" } } as unknown as ReportRunView;
    expect(runDocument(malformed).available).toBe(false);
    const good = { ...run("r3", 1, "SUCCEEDED", 1), document: { sections: [{ id: "a", title: "A", notes: ["n"] }] } } as unknown as ReportRunView;
    expect(runDocument(good)).toEqual({ available: true, sections: [{ id: "a", title: "A", notes: ["n"] }] });
  });
});
